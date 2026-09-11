#!/usr/bin/env bash
set -euo pipefail

# Defaults to the microservice Golden Path; monolith qualification is explicit.
# The shared client contract lives only in blackbox.py.
HARNESS_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$HARNESS_DIR/../../.." && pwd)"
TOPOLOGY="${1:-microservices}"

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
    -u REFERENCE_PORT_MANIFEST -u REFERENCE_STDOUT_EVIDENCE_DIR \
    -u REFERENCE_CLEANUP_STATUS_FILE "$1"
}

run_harness_self_test "$HARNESS_DIR/tests/test-port-plan.sh"
run_harness_self_test "$HARNESS_DIR/tests/test-cleanup.sh"
run_harness_self_test "$HARNESS_DIR/tests/test-parallel-scenario.sh"

if [[ "${REFERENCE_SKIP_BUILD:-0}" != "1" ]]; then
  build_args=(-Dmonolith.skipITs=true)
  duplicate_tests="OrderApplicationIT, InventoryApplicationIT"
  if [[ "$TOPOLOGY" != "microservices" ]]; then
    build_args=(-Dmonolith.skipITs=false)
    duplicate_tests+=", ModuveraMonolithApplicationIT"
  fi
  "$PROJECT_ROOT/mvnw" -q clean install "${build_args[@]}"
  # These topology-specific tests inject duplicate deliveries below the public seam. Exposing a
  # production test endpoint merely for duplicate injection would weaken the black-box boundary.
  echo "Focused duplicate-delivery evidence: PASS ($duplicate_tests)"
fi

if [[ "$TOPOLOGY" != "all" && "${REFERENCE_KEEP_RUNNING:-0}" == "1" ]]; then
  # The caller must signal the process that owns application cleanup and run locks.
  exec "$HARNESS_DIR/run-topology.sh" "$TOPOLOGY"
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
