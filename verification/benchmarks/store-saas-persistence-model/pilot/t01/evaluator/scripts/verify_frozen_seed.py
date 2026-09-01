#!/usr/bin/env python3
"""Fail closed when a candidate changes frozen seed bytes or writes out of scope."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import sys
from pathlib import Path


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--expected-manifest-sha256", required=True)
    args = parser.parse_args()

    root = args.candidate_root.resolve()
    manifest = root / "frozen-files.sha256"
    errors: list[str] = []
    if not manifest.is_file():
        errors.append("missing frozen-files.sha256")
    elif sha256(manifest) != args.expected_manifest_sha256:
        errors.append("frozen manifest digest changed")

    expected: set[str] = set()
    if not errors:
        for line in manifest.read_text(encoding="utf-8").splitlines():
            digest, separator, relative = line.partition("  ")
            if not separator or len(digest) != 64:
                errors.append(f"invalid manifest line: {line!r}")
                continue
            expected.add(relative)
            path = root / relative
            if not path.is_file() or path.is_symlink():
                errors.append(f"missing or non-regular frozen file: {relative}")
            elif sha256(path) != digest:
                errors.append(f"frozen file changed: {relative}")

    allowlist_path = root / "candidate-write-allowlist.txt"
    allowlist = (
        allowlist_path.read_text(encoding="utf-8").splitlines()
        if allowlist_path.is_file()
        else []
    )
    ignored = {"frozen-files.sha256"}
    for path in sorted(root.rglob("*")):
        relative_path = path.relative_to(root)
        if "target" in relative_path.parts:
            continue
        relative = relative_path.as_posix()
        if path.is_symlink():
            errors.append(f"symlink is not allowed: {relative}")
        if not path.is_file() or relative in expected or relative in ignored:
            continue
        if not any(fnmatch.fnmatch(relative, pattern) for pattern in allowlist):
            errors.append(f"candidate file outside allowlist: {relative}")

    result = {"status": "PASS" if not errors else "FAIL", "errors": errors}
    print(json.dumps(result, sort_keys=True))
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
