#!/usr/bin/env python3
"""Read-only, inexpensive checks before reviewing an immutable commit range."""

from __future__ import annotations

import argparse
import contextlib
import io
import json
from pathlib import Path
import subprocess

import quality_gate


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    options = parser.parse_args()
    report = {"status": "FAIL", "checks": []}
    try:
        base = quality_gate.resolve_commit(options.repo, options.base, "base")
        head = quality_gate.resolve_commit(options.repo, options.head, "head")
        report.update(base=base, head=head)
        if base == head:
            raise quality_gate.GateError("empty commit range")
        if quality_gate.git(options.repo, "merge-base", "--is-ancestor", base, head,
                            check=False).returncode != 0:
            raise quality_gate.GateError("base must be an ancestor of head; resolve the merge base first")
        entries = quality_gate.changed_entries(options.repo, base, head)
        if not entries:
            raise quality_gate.GateError("empty diff")
        whitespace = quality_gate.git(options.repo, "diff", "--check", base, head, check=False)
        report["checks"].append({
            "name": "diff-check", "exit_code": whitespace.returncode,
            "output": whitespace.stdout + whitespace.stderr,
        })
        for name, check in (
            ("markdown-links", lambda: quality_gate.check_markdown_links(
                options.repo, head, quality_gate.changed_markdown_paths(entries))),
            ("skill-structure", lambda: quality_gate.check_skill_structure(options.repo, head)),
        ):
            output = io.StringIO()
            exit_code = 0
            with contextlib.redirect_stdout(output):
                try:
                    check()
                except quality_gate.GateError as error:
                    exit_code = 1
                    print(error)
            report["checks"].append({
                "name": name, "exit_code": exit_code, "output": output.getvalue(),
            })
        if all(check["exit_code"] == 0 for check in report["checks"]):
            report["status"] = "PASS"
    except (quality_gate.GateError, OSError, subprocess.CalledProcessError) as error:
        report["error"] = str(error)
    print(json.dumps(report, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
