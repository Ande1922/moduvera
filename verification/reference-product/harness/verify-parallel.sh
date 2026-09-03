#!/usr/bin/env bash
set -euo pipefail

# Explicit high-cost scenario: run both supported public contracts concurrently without duplicating them.
HARNESS_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$HARNESS_DIR/../../.." && pwd)"
MICROSERVICES_SLOT="${1:-${REFERENCE_PARALLEL_MICROSERVICES_SLOT:-40}}"
MONOLITH_SLOT="${2:-${REFERENCE_PARALLEL_MONOLITH_SLOT:-41}}"
MICROSERVICES_PID=""
MONOLITH_PID=""
SCENARIO_STATUS=1
EVIDENCE_IS_TEMP=0

if [[ "$MICROSERVICES_SLOT" == "$MONOLITH_SLOT" ]]; then
  echo "Parallel reference scenario requires two distinct run slots" >&2
  exit 64
fi
if [[ "${REFERENCE_KEEP_RUNNING:-0}" == "1" || "${REFERENCE_PREFLIGHT_ONLY:-0}" == "1" ]]; then
  echo "Parallel reference scenario does not support KEEP_RUNNING or PREFLIGHT_ONLY" >&2
  exit 64
fi

for override in \
  REFERENCE_POSTGRES_PORT REFERENCE_KAFKA_PORT REFERENCE_GATEWAY_PORT REFERENCE_IDENTITY_PORT \
  REFERENCE_CATALOG_PORT REFERENCE_ORDER_PORT REFERENCE_INVENTORY_PORT REFERENCE_MONOLITH_PORT \
  REFERENCE_GATEWAY_DEBUG_PORT REFERENCE_IDENTITY_DEBUG_PORT REFERENCE_CATALOG_DEBUG_PORT \
  REFERENCE_ORDER_DEBUG_PORT REFERENCE_INVENTORY_DEBUG_PORT REFERENCE_MONOLITH_DEBUG_PORT \
  REFERENCE_COMPOSE_PROJECT REFERENCE_PORT_MANIFEST; do
  if [[ -n "${!override:-}" ]]; then
    echo "Parallel reference scenario owns deterministic resources; unset $override" >&2
    exit 64
  fi
done

if [[ -n "${REFERENCE_PARALLEL_EVIDENCE_DIR:-}" ]]; then
  EVIDENCE_DIR="$REFERENCE_PARALLEL_EVIDENCE_DIR"
  mkdir "$EVIDENCE_DIR"
else
  EVIDENCE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-reference-parallel.XXXXXX")"
  EVIDENCE_IS_TEMP=1
fi
EVIDENCE_DIR="$(cd "$EVIDENCE_DIR" && pwd)"
MICROSERVICES_MANIFEST="$EVIDENCE_DIR/microservices-manifest.json"
MONOLITH_MANIFEST="$EVIDENCE_DIR/business-core-monolith-manifest.json"
MICROSERVICES_LOG="$EVIDENCE_DIR/microservices.log"
MONOLITH_LOG="$EVIDENCE_DIR/business-core-monolith.log"

stop_children() {
  local pid
  for pid in "$MICROSERVICES_PID" "$MONOLITH_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
  for pid in "$MICROSERVICES_PID" "$MONOLITH_PID"; do
    [[ -n "$pid" ]] && wait "$pid" 2>/dev/null || true
  done
}

cleanup() {
  local exit_code=$?
  stop_children
  if [[ $SCENARIO_STATUS -eq 0 && $EVIDENCE_IS_TEMP -eq 1 && "${REFERENCE_KEEP_PARALLEL_EVIDENCE:-0}" != "1" ]]; then
    rm -rf "$EVIDENCE_DIR"
  else
    echo "Parallel reference evidence retained at $EVIDENCE_DIR" >&2
  fi
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$HARNESS_DIR/tests/test-port-plan.sh"
"$HARNESS_DIR/tests/test-parallel-scenario.sh"
if [[ "${REFERENCE_SKIP_BUILD:-0}" != "1" ]]; then
  "$PROJECT_ROOT/mvnw" -q clean install
fi

echo "Starting parallel reference scenario: microservices slot $MICROSERVICES_SLOT; business-core-monolith slot $MONOLITH_SLOT"
RUN_SLOT="$MICROSERVICES_SLOT" REFERENCE_PORT_MANIFEST="$MICROSERVICES_MANIFEST" \
  "$HARNESS_DIR/run-topology.sh" microservices >"$MICROSERVICES_LOG" 2>&1 &
MICROSERVICES_PID=$!
RUN_SLOT="$MONOLITH_SLOT" REFERENCE_PORT_MANIFEST="$MONOLITH_MANIFEST" \
  "$HARNESS_DIR/run-topology.sh" business-core-monolith >"$MONOLITH_LOG" 2>&1 &
MONOLITH_PID=$!

set +e
wait "$MICROSERVICES_PID"
MICROSERVICES_STATUS=$?
MICROSERVICES_PID=""
wait "$MONOLITH_PID"
MONOLITH_STATUS=$?
MONOLITH_PID=""
set -e

if [[ $MICROSERVICES_STATUS -ne 0 || $MONOLITH_STATUS -ne 0 ]]; then
  echo "Parallel reference scenario failed: microservices=$MICROSERVICES_STATUS business-core-monolith=$MONOLITH_STATUS" >&2
  echo "--- microservices (last 80 lines)" >&2
  tail -80 "$MICROSERVICES_LOG" >&2 || true
  echo "--- business-core-monolith (last 80 lines)" >&2
  tail -80 "$MONOLITH_LOG" >&2 || true
  exit 1
fi

python3 "$HARNESS_DIR/validate-parallel-scenario.py" \
  "$MICROSERVICES_MANIFEST" "$MICROSERVICES_LOG" \
  "$MONOLITH_MANIFEST" "$MONOLITH_LOG"
SCENARIO_STATUS=0
echo "Parallel reference product verification: PASS (microservices slot $MICROSERVICES_SLOT; business-core-monolith slot $MONOLITH_SLOT)"
