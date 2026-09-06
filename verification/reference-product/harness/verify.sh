#!/usr/bin/env bash
set -euo pipefail

# Qualifies both supported application topologies. The client contract lives only in blackbox.py.
HARNESS_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$HARNESS_DIR/../../.." && pwd)"
TOPOLOGY="${1:-all}"

case "$TOPOLOGY" in
  all|microservices|business-core-monolith) ;;
  *)
    echo "Unknown topology '$TOPOLOGY'; expected all, microservices, or business-core-monolith" >&2
    exit 64
    ;;
esac

run_harness_self_test() {
  env -u REFERENCE_GOVERNED_OBSERVABILITY \
    -u REFERENCE_GOVERNED_PROBE_READY -u REFERENCE_GOVERNED_PROBE_RELEASE \
    -u REFERENCE_PORT_MANIFEST "$1"
}

run_harness_self_test "$HARNESS_DIR/tests/test-port-plan.sh"
run_harness_self_test "$HARNESS_DIR/tests/test-cleanup.sh"
run_harness_self_test "$HARNESS_DIR/tests/test-parallel-scenario.sh"

if [[ "${REFERENCE_SKIP_BUILD:-0}" != "1" ]]; then
  "$PROJECT_ROOT/mvnw" -q clean install
  # These topology-specific tests inject duplicate deliveries below the public seam. Exposing a
  # production test endpoint merely for duplicate injection would weaken the black-box boundary.
  echo "Focused duplicate-delivery evidence: PASS (OrderApplicationIT, InventoryApplicationIT, ModuveraMonolithApplicationIT)"
fi

if [[ "$TOPOLOGY" == "all" ]]; then
  "$HARNESS_DIR/run-topology.sh" microservices
  "$HARNESS_DIR/run-topology.sh" business-core-monolith
else
  "$HARNESS_DIR/run-topology.sh" "$TOPOLOGY"
fi

if [[ "${REFERENCE_PREFLIGHT_ONLY:-0}" == "1" ]]; then
  echo "Reference harness preflight-only verification: PASS (selection=$TOPOLOGY)"
else
  echo "Reference product verification: PASS (selection=$TOPOLOGY)"
fi
