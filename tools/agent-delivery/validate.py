#!/usr/bin/env python3
"""Validate the repository-owned agent delivery workflow."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from delivery_contract import validate_repository


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    options = parser.parse_args()
    failures = validate_repository(options.repo)
    if failures:
        print("agent delivery validation failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("validated 9 self-contained project Skills and delivery routing")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
