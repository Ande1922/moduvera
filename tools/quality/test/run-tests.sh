#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec python3 -m unittest -v "$SCRIPT_DIR/test_quality_gate.py"
