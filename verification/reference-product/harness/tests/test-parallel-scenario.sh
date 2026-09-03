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
  early-descendant:*)
    python3 - "$STUB_STATE_DIR/$topology.early-descendant-pid" <<'PY'
import os
import pathlib
import signal
import sys
import time

descendant = os.fork()
if descendant == 0:
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    while True:
        time.sleep(1)
pathlib.Path(sys.argv[1]).write_text(str(descendant), encoding="ascii")
PY
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

STUB_VALIDATOR="$TEST_DIR/stub-validator.py"
cat > "$STUB_VALIDATOR" <<'PY'
#!/usr/bin/env python3
import os
import pathlib
import signal
import sys
import time

mode = os.environ.get("STUB_VALIDATOR_MODE")
state = pathlib.Path(os.environ["STUB_STATE_DIR"])
if mode == "early-descendant":
    descendant = os.fork()
    if descendant == 0:
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        while True:
            time.sleep(1)
    (state / "validator.early-descendant-pid").write_text(
        str(descendant), encoding="ascii"
    )
if mode != "hang":
    os.execv(sys.executable, [sys.executable, os.environ["REAL_VALIDATOR"], *sys.argv[1:]])

signal.signal(signal.SIGINT, signal.SIG_IGN)
signal.signal(signal.SIGTERM, signal.SIG_IGN)
(state / "validator.wrapper-pid").write_text(str(os.getpid()), encoding="ascii")
descendant = os.fork()
if descendant == 0:
    while True:
        time.sleep(1)
(state / "validator.descendant-pid").write_text(str(descendant), encoding="ascii")
while True:
    time.sleep(1)
PY
chmod +x "$STUB_VALIDATOR"

run_supervisor() {
  local name="$1" mode="$2" expected_status="$3" evidence_kind="$4"
  local validator="${5:-$HARNESS_DIR/validate-parallel-scenario.py}"
  local case_dir="$TEST_DIR/$name" status
  mkdir "$case_dir" "$case_dir/state" "$case_dir/tmp"
  set +e
  if [[ "$evidence_kind" == "explicit" ]]; then
    STUB_STATE_DIR="$case_dir/state" STUB_RUNNER_MODE="$mode" \
      REFERENCE_PARALLEL_EVIDENCE_DIR="$case_dir/evidence" \
      python3 "$HARNESS_DIR/parallel_supervisor.py" \
        --runner "$STUB_RUNNER" --validator "$validator" \
        20 21 >"$case_dir/out" 2>"$case_dir/err"
  else
    env -u REFERENCE_PARALLEL_EVIDENCE_DIR -u REFERENCE_KEEP_PARALLEL_EVIDENCE \
      STUB_STATE_DIR="$case_dir/state" STUB_RUNNER_MODE="$mode" TMPDIR="$case_dir/tmp" \
      python3 "$HARNESS_DIR/parallel_supervisor.py" \
        --runner "$STUB_RUNNER" --validator "$validator" \
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

REFERENCE_PARALLEL_TERM_TIMEOUT_SECONDS=0.2 \
  run_supervisor runner-early-descendant early-descendant 0 explicit
for pid_file in "$TEST_DIR/runner-early-descendant/state"/*.early-descendant-pid; do
  pid="$(<"$pid_file")"
  for _ in {1..40}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.05; done
  if kill -0 "$pid" 2>/dev/null; then
    fail "successful runner descendant $pid survived process-group drain"
  fi
done

STUB_VALIDATOR_MODE=early-descendant \
  REAL_VALIDATOR="$HARNESS_DIR/validate-parallel-scenario.py" \
  REFERENCE_PARALLEL_TERM_TIMEOUT_SECONDS=0.2 \
  run_supervisor validator-early-descendant success 0 explicit "$STUB_VALIDATOR"
VALIDATOR_EARLY_PID="$(<"$TEST_DIR/validator-early-descendant/state/validator.early-descendant-pid")"
for _ in {1..40}; do kill -0 "$VALIDATOR_EARLY_PID" 2>/dev/null || break; sleep 0.05; done
if kill -0 "$VALIDATOR_EARLY_PID" 2>/dev/null; then
  fail "successful validator descendant $VALIDATOR_EARLY_PID survived process-group drain"
fi

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

run_validator_signal_case() {
  local name="$1" signal_name="$2" signal_number="$3" expected_status="$4"
  local signal_dir="$TEST_DIR/$name" start_seconds signal_status pid_file pid
  mkdir "$signal_dir" "$signal_dir/state"
  start_seconds=$SECONDS
  STUB_STATE_DIR="$signal_dir/state" STUB_RUNNER_MODE=success STUB_VALIDATOR_MODE=hang \
    REAL_VALIDATOR="$HARNESS_DIR/validate-parallel-scenario.py" \
    REFERENCE_PARALLEL_TERM_TIMEOUT_SECONDS=0.2 \
    REFERENCE_PARALLEL_VALIDATOR_TIMEOUT_SECONDS=30 \
    REFERENCE_PARALLEL_EVIDENCE_DIR="$signal_dir/evidence" \
    python3 "$HARNESS_DIR/parallel_supervisor.py" \
      --runner "$STUB_RUNNER" --validator "$STUB_VALIDATOR" \
      20 21 >"$signal_dir/out" 2>"$signal_dir/err" &
  SUPERVISOR_PID=$!
  for _ in {1..150}; do
    [[ -f "$signal_dir/state/validator.descendant-pid" ]] && break
    sleep 0.02
  done
  [[ -f "$signal_dir/state/validator.descendant-pid" ]] \
    || fail "$signal_name blocked-validator test did not start"
  kill -s "$signal_name" "$SUPERVISOR_PID"
  set +e
  wait "$SUPERVISOR_PID"
  signal_status=$?
  set -e
  SUPERVISOR_PID=""
  [[ $signal_status -eq $expected_status ]] \
    || fail "blocked validator $signal_name returned $signal_status instead of $expected_status"
  (( SECONDS - start_seconds < 5 )) \
    || fail "blocked validator $signal_name escalation exceeded its bounded timeout"
  grep -F "interrupted by signal $signal_number" "$signal_dir/err" >/dev/null \
    || fail "blocked validator $signal_name interruption was not diagnosed"
  for pid_file in "$signal_dir/state"/validator.*-pid; do
    pid="$(<"$pid_file")"
    for _ in {1..40}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.05; done
    if kill -0 "$pid" 2>/dev/null; then
      fail "blocked validator process $pid survived $signal_name escalation"
    fi
  done
}

run_validator_signal_case validator-term TERM 15 143
run_validator_signal_case validator-int INT 2 130

VALIDATOR_TIMEOUT_DIR="$TEST_DIR/validator-timeout"
mkdir "$VALIDATOR_TIMEOUT_DIR" "$VALIDATOR_TIMEOUT_DIR/state"
set +e
STUB_STATE_DIR="$VALIDATOR_TIMEOUT_DIR/state" STUB_RUNNER_MODE=success STUB_VALIDATOR_MODE=hang \
  REAL_VALIDATOR="$HARNESS_DIR/validate-parallel-scenario.py" \
  REFERENCE_PARALLEL_TERM_TIMEOUT_SECONDS=0.2 \
  REFERENCE_PARALLEL_VALIDATOR_TIMEOUT_SECONDS=0.2 \
  REFERENCE_PARALLEL_EVIDENCE_DIR="$VALIDATOR_TIMEOUT_DIR/evidence" \
  python3 "$HARNESS_DIR/parallel_supervisor.py" \
    --runner "$STUB_RUNNER" --validator "$STUB_VALIDATOR" \
    20 21 >"$VALIDATOR_TIMEOUT_DIR/out" 2>"$VALIDATOR_TIMEOUT_DIR/err"
validator_timeout_status=$?
set -e
[[ $validator_timeout_status -eq 124 ]] \
  || fail "blocked validator timeout returned $validator_timeout_status instead of 124"
grep -F 'Parallel evidence validator exceeded 0.2 seconds' \
  "$VALIDATOR_TIMEOUT_DIR/err" >/dev/null \
  || fail "blocked validator timeout was not diagnosed"
for pid_file in "$VALIDATOR_TIMEOUT_DIR/state"/validator.*-pid; do
  pid="$(<"$pid_file")"
  for _ in {1..40}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.05; done
  if kill -0 "$pid" 2>/dev/null; then
    fail "timed-out validator process $pid survived escalation"
  fi
done

for timeout_name in \
  REFERENCE_PARALLEL_TERM_TIMEOUT_SECONDS \
  REFERENCE_PARALLEL_VALIDATOR_TIMEOUT_SECONDS; do
  for invalid_timeout in inf -inf nan -1; do
    set +e
    env "$timeout_name=$invalid_timeout" \
      python3 "$HARNESS_DIR/parallel_supervisor.py" \
        --runner "$STUB_RUNNER" --validator "$HARNESS_DIR/validate-parallel-scenario.py" \
        20 21 >"$TEST_DIR/invalid-timeout.out" 2>"$TEST_DIR/invalid-timeout.err"
    invalid_timeout_status=$?
    set -e
    [[ $invalid_timeout_status -ne 0 ]] \
      || fail "$timeout_name accepted non-finite value $invalid_timeout"
    grep -F "Invalid $timeout_name; expected a finite non-negative number" \
      "$TEST_DIR/invalid-timeout.err" >/dev/null \
      || fail "$timeout_name did not diagnose non-finite value $invalid_timeout"
  done
done

if grep -F 'verify-parallel.sh' "$HARNESS_DIR/verify.sh" >/dev/null; then
  fail "ordinary verify.sh unexpectedly pays for the parallel scenario"
fi

echo "Parallel scenario orchestration tests: PASS"
