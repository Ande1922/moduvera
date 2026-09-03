#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

case "${1:-auto}" in
  auto|normal|--base|--head|--ref|-h|--help) ;;
  *)
    echo "quality gate mode must be auto or normal" >&2
    exit 2
    ;;
esac

exec python3 "$SCRIPT_DIR/quality_gate.py" "$@"
