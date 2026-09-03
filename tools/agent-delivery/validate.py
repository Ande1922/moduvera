#!/usr/bin/env python3
"""Validate the repository-owned agent delivery workflow."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from delivery_contract import representative_forward_failures, validate_repository


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    options = parser.parse_args()
    failures = [
        *validate_repository(options.repo),
        *representative_forward_failures(options.repo),
    ]
    if failures:
        print("agent delivery validation failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    skill_count = len([path for path in (options.repo / ".agents/skills").iterdir()
                       if path.is_dir()])
    print(
        f"validated {skill_count} self-contained project Skills, "
        "delivery routing, and representative forward evidence"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
