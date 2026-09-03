#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-parallel-scenario-test.XXXXXX")"
SUPERVISOR_PID=""

cleanup() {
  if [[ -n "$SUPERVISOR_PID" ]] && kill -0 "$SUPERVISOR_PID" 2>/dev/null; then
    kill -TERM "$SUPERVISOR_PID" 2>/dev/null || true
    wait "$SUPERVISOR_PID" 2>/dev/null || true
  fi
  rm -rf "$TEST_DIR"
}
trap cleanup EXIT

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

STUB_RUNNER="$TEST_DIR/stub-runner.sh"
cat > "$STUB_RUNNER" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
topology="$1"
peer=microservices
[[ "$topology" == "microservices" ]] && peer=business-core-monolith
mkdir -p "$STUB_STATE_DIR"
: > "$STUB_STATE_DIR/$topology.started"
for _ in {1..100}; do
  [[ -f "$STUB_STATE_DIR/$peer.started" ]] && break
  sleep 0.02
done
[[ -f "$STUB_STATE_DIR/$peer.started" ]] || { echo "peer did not start concurrently" >&2; exit 91; }

python3 - "$REFERENCE_PORT_MANIFEST" "$topology" "$RUN_SLOT" <<'PY'
import json
import pathlib
import sys

path, topology, raw_slot = sys.argv[1:]
slot = int(raw_slot)
identity = f"{topology}-slot{slot}"
manifest = {
    "schemaVersion": 1,
    "runSlot": slot,
    "portStride": 100,
    "topology": topology,
    "ports": {
        "postgres": 20000 + slot,
        "kafka": 30000 + slot,
        "apps": {"gateway": {"application": 10000 + slot, "management": 10000 + slot}},
    },
    "debugEnabled": False,
    "resources": {
        "runId": f"run-{identity}",
        "composeProject": f"compose-{identity}",
        "dataNamespace": f"data-{identity}",
        "tempDirectory": f"/tmp/{identity}",
        "topics": {
            "inventoryReserve": f"reserve-{identity}",
            "inventoryResult": f"result-{identity}",
        },
    },
}
pathlib.Path(path).write_text(json.dumps(manifest), encoding="utf-8")
PY

case "${STUB_RUNNER_MODE:-success}:$topology" in
  fail-microservices:microservices) exit 17 ;;
  fail-monolith:business-core-monolith) exit 18 ;;
  cleanup-failure:microservices)
    echo "Reference cleanup failure: injected runner cleanup failure" >&2
    exit 70
    ;;
  hang:*)
    trap '' INT TERM
    echo "$$" > "$STUB_STATE_DIR/$topology.wrapper-pid"
    (trap '' INT TERM; while true; do sleep 1; done) &
    echo "$!" > "$STUB_STATE_DIR/$topology.descendant-pid"
    while true; do sleep 1; done
    ;;
esac

sleep 0.15
echo "reference acceptance: PASS (topology=$topology; stub contract)"
echo "Reference product verification: PASS (topology=$topology; public contract + Kafka recovery)"
SH
chmod +x "$STUB_RUNNER"

run_supervisor() {
  local name="$1" mode="$2" expected_status="$3" evidence_kind="$4"
  local case_dir="$TEST_DIR/$name" status
  mkdir "$case_dir" "$case_dir/state" "$case_dir/tmp"
  set +e
  if [[ "$evidence_kind" == "explicit" ]]; then
    STUB_STATE_DIR="$case_dir/state" STUB_RUNNER_MODE="$mode" \
      REFERENCE_PARALLEL_EVIDENCE_DIR="$case_dir/evidence" \
      python3 "$HARNESS_DIR/parallel_supervisor.py" \
        --runner "$STUB_RUNNER" --validator "$HARNESS_DIR/validate-parallel-scenario.py" \
        20 21 >"$case_dir/out" 2>"$case_dir/err"
  else
    env -u REFERENCE_PARALLEL_EVIDENCE_DIR -u REFERENCE_KEEP_PARALLEL_EVIDENCE \
      STUB_STATE_DIR="$case_dir/state" STUB_RUNNER_MODE="$mode" TMPDIR="$case_dir/tmp" \
      python3 "$HARNESS_DIR/parallel_supervisor.py" \
        --runner "$STUB_RUNNER" --validator "$HARNESS_DIR/validate-parallel-scenario.py" \
        20 21 >"$case_dir/out" 2>"$case_dir/err"
  fi
  status=$?
  set -e
  [[ "$status" == "$expected_status" ]] \
    || fail "$name returned $status instead of $expected_status"
}

run_supervisor success success 0 explicit
[[ -f "$TEST_DIR/success/evidence/microservices-manifest.json" ]] \
  || fail "caller-selected success evidence was not retained"
grep -F 'Parallel reference product verification: PASS' "$TEST_DIR/success/out" >/dev/null \
  || fail "parallel success was not reported"

run_supervisor temporary-success success 0 temporary
[[ -z "$(find "$TEST_DIR/temporary-success/tmp" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
  || fail "successful temporary evidence was not removed"

run_supervisor microservices-failure fail-microservices 1 explicit
grep -F 'microservices=17' "$TEST_DIR/microservices-failure/err" >/dev/null \
  || fail "microservices failure status was not reported"
[[ -d "$TEST_DIR/microservices-failure/evidence" ]] \
  || fail "failure evidence was not retained"

run_supervisor monolith-failure fail-monolith 1 explicit
grep -F 'business-core-monolith=18' "$TEST_DIR/monolith-failure/err" >/dev/null \
  || fail "monolith failure status was not reported"

run_supervisor cleanup-failure cleanup-failure 1 explicit
grep -F 'Reference cleanup failure: injected runner cleanup failure' \
  "$TEST_DIR/cleanup-failure/err" >/dev/null \
  || fail "topology cleanup failure evidence was not propagated"

run_signal_case() {
  local name="$1" signal_name="$2" signal_number="$3" expected_status="$4"
  local signal_dir="$TEST_DIR/$name" start_seconds signal_status pid_file pid
  mkdir "$signal_dir" "$signal_dir/state"
  start_seconds=$SECONDS
  STUB_STATE_DIR="$signal_dir/state" STUB_RUNNER_MODE=hang \
    REFERENCE_PARALLEL_TERM_TIMEOUT_SECONDS=0.2 \
    REFERENCE_PARALLEL_EVIDENCE_DIR="$signal_dir/evidence" \
    python3 "$HARNESS_DIR/parallel_supervisor.py" \
      --runner "$STUB_RUNNER" --validator "$HARNESS_DIR/validate-parallel-scenario.py" \
      20 21 >"$signal_dir/out" 2>"$signal_dir/err" &
  SUPERVISOR_PID=$!
  for _ in {1..100}; do
    [[ -f "$signal_dir/state/microservices.descendant-pid" \
      && -f "$signal_dir/state/business-core-monolith.descendant-pid" ]] && break
    sleep 0.02
  done
  [[ -f "$signal_dir/state/microservices.descendant-pid" ]] \
    || fail "$signal_name test topology groups did not start"
  kill -s "$signal_name" "$SUPERVISOR_PID"
  set +e
  wait "$SUPERVISOR_PID"
  signal_status=$?
  set -e
  SUPERVISOR_PID=""
  [[ $signal_status -eq $expected_status ]] \
    || fail "$signal_name interruption returned $signal_status instead of $expected_status"
  (( SECONDS - start_seconds < 5 )) \
    || fail "$signal_name escalation exceeded its bounded timeout"
  grep -F "interrupted by signal $signal_number" "$signal_dir/err" >/dev/null \
    || fail "$signal_name interruption was not diagnosed"
  for pid_file in "$signal_dir/state"/*.wrapper-pid "$signal_dir/state"/*.descendant-pid; do
    pid="$(<"$pid_file")"
    for _ in {1..40}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.05; done
    if kill -0 "$pid" 2>/dev/null; then
      fail "process-group descendant $pid survived bounded $signal_name escalation"
    fi
  done
}

run_signal_case signal-term TERM 15 143
run_signal_case signal-int INT 2 130

if grep -F 'verify-parallel.sh' "$HARNESS_DIR/verify.sh" >/dev/null; then
  fail "ordinary verify.sh unexpectedly pays for the parallel scenario"
fi

echo "Parallel scenario orchestration tests: PASS"
