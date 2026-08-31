#!/usr/bin/env python3
"""Prepare or verify a mutable preflight preview; this never invokes Codex."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from runner_core import prepare_run, verify_prepared_run


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--spec", type=Path, required=True)
    prepare.add_argument("--run-root", type=Path, required=True)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--run-root", type=Path, required=True)
    args = parser.parse_args()

    if args.command == "prepare":
        result = prepare_run(args.spec, args.run_root)
        print(json.dumps(result, sort_keys=True))
        return 0 if result["state"] == "PREFLIGHT_READY" else 3
    result = verify_prepared_run(args.run_root)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
