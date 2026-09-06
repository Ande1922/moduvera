#!/usr/bin/env python3
"""Read-only, inexpensive checks before reviewing an immutable commit range."""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess

import quality_gate


def checkout_identity(repo: Path) -> dict:
    branch = quality_gate.git(repo, "symbolic-ref", "--quiet", "--short", "HEAD", check=False)
    if branch.returncode not in (0, 1):
        raise quality_gate.GateError("cannot inspect checkout branch")
    root = quality_gate.git(repo, "rev-parse", "--show-toplevel").stdout.strip()
    return {
        "worktree": str(Path(root).resolve()),
        "branch": branch.stdout.strip() if branch.returncode == 0 else None,
        "head": quality_gate.resolve_commit(repo, "HEAD", "head"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--require-current-clean", action="store_true",
                        help="Also verify current HEAD and clean checkout at both ends of the check")
    parser.add_argument("--expected-branch",
                        help="Require this registered branch; use with --require-current-clean")
    options = parser.parse_args()
    report = {
        "schema": 1,
        "kind": "review-preflight",
        "status": "FAIL",
        "exit_code": 1,
        "scope": "current-clean-checkout" if options.require_current_clean else "committed-range",
        "checks": [],
    }
    try:
        if options.expected_branch is not None and not options.require_current_clean:
            raise quality_gate.GateError("--expected-branch requires --require-current-clean")
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
        report["checkout"] = checkout_identity(options.repo)
        if options.require_current_clean:
            quality_gate.ensure_pinned_checkout(options.repo, head)
            if (options.expected_branch is not None
                    and report["checkout"]["branch"] != options.expected_branch):
                raise quality_gate.GateError("checkout branch does not match registered --expected-branch")
        raw_diff = subprocess.run(
            ["git", "diff-tree", "--raw", "-r", "-z", "--no-commit-id",
             "--no-renames", "--no-abbrev", "--no-ext-diff", "--no-textconv",
             "--no-color", "--no-relative", "--ignore-submodules=none",
             "-O", os.devnull, base, head, "--"],
            cwd=options.repo, capture_output=True, check=True,
        ).stdout
        report.update(diff_sha256=hashlib.sha256(raw_diff).hexdigest(),
                      diff_format="git-diff-tree-raw-v1", changed_file_count=len(entries))
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
        if options.require_current_clean:
            quality_gate.ensure_pinned_checkout(options.repo, head)
            if checkout_identity(options.repo) != report["checkout"]:
                raise quality_gate.GateError("checkout identity changed during preflight")
        if all(check["exit_code"] == 0 for check in report["checks"]):
            report["status"] = "PASS"
            report["exit_code"] = 0
    except (quality_gate.GateError, OSError, subprocess.CalledProcessError) as error:
        report["error"] = str(error)
    report["checked_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
    print(json.dumps(report, indent=2))
    return report["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
