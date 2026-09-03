#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-parallel-scenario-test.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT

fail() {
  echo "parallel-scenario test failed: $*" >&2
  exit 1
}

write_manifest() {
  local topology="$1" slot="$2" manifest="$3" identity="$4"
  (
    # shellcheck source=../port-plan.sh
    source "$HARNESS_DIR/port-plan.sh"
    RUN_SLOT="$slot"
    reference_configure_port_plan "$topology"
    REFERENCE_MANIFEST_RUN_ID="run-$identity"
    REFERENCE_MANIFEST_COMPOSE_PROJECT="compose-$identity"
    REFERENCE_MANIFEST_RUN_DIRECTORY="$TEST_DIR/temp-$identity"
    REFERENCE_MANIFEST_INVENTORY_RESERVE_TOPIC="inventory-reserve-$identity"
    REFERENCE_MANIFEST_INVENTORY_RESULT_TOPIC="inventory-result-$identity"
    reference_write_port_manifest "$topology" "$manifest" >/dev/null
  )
}

write_manifest microservices 20 "$TEST_DIR/microservices.json" micro
write_manifest business-core-monolith 21 "$TEST_DIR/monolith.json" monolith
printf '%s\n' \
  'reference acceptance: PASS (topology=microservices; public contract fixture)' \
  'Reference product verification: PASS (topology=microservices; public contract + Kafka recovery)' \
  > "$TEST_DIR/microservices.log"
printf '%s\n' \
  'reference acceptance: PASS (topology=business-core-monolith; public contract fixture)' \
  'Reference product verification: PASS (topology=business-core-monolith; public contract + Kafka recovery)' \
  > "$TEST_DIR/monolith.log"

python3 "$HARNESS_DIR/validate-parallel-scenario.py" \
  "$TEST_DIR/microservices.json" "$TEST_DIR/microservices.log" \
  "$TEST_DIR/monolith.json" "$TEST_DIR/monolith.log" \
  > "$TEST_DIR/summary.json"
grep -F '"status":"PASS"' "$TEST_DIR/summary.json" >/dev/null \
  || fail "valid isolated evidence did not pass"

python3 - \
  "$TEST_DIR/microservices.json" "$TEST_DIR/monolith.json" \
  "$TEST_DIR/monolith-port-overlap.json" "$TEST_DIR/monolith-resource-overlap.json" <<'PY'
import json
import pathlib
import sys

micro = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
monolith = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))

port_overlap = json.loads(json.dumps(monolith))
port_overlap["ports"]["postgres"] = micro["ports"]["postgres"]
pathlib.Path(sys.argv[3]).write_text(json.dumps(port_overlap), encoding="utf-8")

resource_overlap = json.loads(json.dumps(monolith))
resource_overlap["resources"]["composeProject"] = micro["resources"]["composeProject"]
pathlib.Path(sys.argv[4]).write_text(json.dumps(resource_overlap), encoding="utf-8")
PY
if python3 "$HARNESS_DIR/validate-parallel-scenario.py" \
  "$TEST_DIR/microservices.json" "$TEST_DIR/microservices.log" \
  "$TEST_DIR/monolith-port-overlap.json" "$TEST_DIR/monolith.log" \
  >"$TEST_DIR/overlap.out" 2>"$TEST_DIR/overlap.err"; then
  fail "overlapping manifest ports unexpectedly passed"
fi
grep -F 'host ports overlap' "$TEST_DIR/overlap.err" >/dev/null \
  || fail "overlapping ports were not diagnosed"

if python3 "$HARNESS_DIR/validate-parallel-scenario.py" \
  "$TEST_DIR/microservices.json" "$TEST_DIR/microservices.log" \
  "$TEST_DIR/monolith-resource-overlap.json" "$TEST_DIR/monolith.log" \
  >"$TEST_DIR/resource-overlap.out" 2>"$TEST_DIR/resource-overlap.err"; then
  fail "overlapping manifest resource identities unexpectedly passed"
fi
grep -F 'resources.composeProject is not distinct' "$TEST_DIR/resource-overlap.err" >/dev/null \
  || fail "overlapping Compose project identity was not diagnosed"

grep -F 'run-topology.sh" microservices' "$HARNESS_DIR/verify-parallel.sh" >/dev/null \
  || fail "parallel driver does not reuse the microservices topology runner"
grep -F 'run-topology.sh" business-core-monolith' "$HARNESS_DIR/verify-parallel.sh" >/dev/null \
  || fail "parallel driver does not reuse the monolith topology runner"
grep -F 'wait "$MICROSERVICES_PID"' "$HARNESS_DIR/verify-parallel.sh" >/dev/null \
  || fail "parallel driver does not wait for microservices"
grep -F 'wait "$MONOLITH_PID"' "$HARNESS_DIR/verify-parallel.sh" >/dev/null \
  || fail "parallel driver does not wait for monolith"
if grep -F 'verify-parallel.sh' "$HARNESS_DIR/verify.sh" >/dev/null; then
  fail "ordinary verify.sh unexpectedly pays for the parallel scenario"
fi

echo "Parallel scenario orchestration tests: PASS"
