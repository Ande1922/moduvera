#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-cleanup-test.XXXXXX")"
REAL_PYTHON="$(command -v python3)"
BOUNDED_PID=""
cleanup_test_directory() {
  if [[ -n "$BOUNDED_PID" ]] && kill -0 "$BOUNDED_PID" 2>/dev/null; then
    kill -KILL "$BOUNDED_PID" 2>/dev/null || true
    wait "$BOUNDED_PID" 2>/dev/null || true
  fi
  rm -rf "$TEST_DIR"
}
trap cleanup_test_directory EXIT

fail() {
  echo "cleanup test failed: $*" >&2
  exit 1
}

STUB_BIN="$TEST_DIR/bin"
mkdir "$STUB_BIN"

cat > "$STUB_BIN/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ " $* " == *" ps "* && "${STUB_CLEANUP_MODE:-}" == "diagnostics-hang" ]]; then
  echo "$$" >> "$STUB_STATE_DIR/diagnostics-pids"
  (trap '' INT TERM; while true; do sleep 1; done) &
  echo "$!" >> "$STUB_STATE_DIR/diagnostics-pids"
  trap '' INT TERM
  while true; do sleep 1; done
fi
if [[ " $* " == *" logs postgres "* ]]; then
  echo "PostgreSQL init process complete"
  exit 0
fi
if [[ " $* " == *" up "* ]]; then
  : > "$STUB_STATE_DIR/compose-started"
fi
if [[ " $* " == *" down "* ]]; then
  if [[ -f "$STUB_STATE_DIR/compose-started" ]]; then
    : > "$STUB_STATE_DIR/compose-down-started"
    if [[ "${STUB_CLEANUP_MODE:-}" == "compose-failure" ]]; then
      exit 42
    fi
    if [[ "${STUB_CLEANUP_MODE:-}" == "compose-hang" ]]; then
      echo "$$" >> "$STUB_STATE_DIR/docker-pids"
      (trap '' INT TERM; while true; do sleep 1; done) &
      echo "$!" >> "$STUB_STATE_DIR/docker-pids"
      trap '' INT TERM
      while true; do sleep 1; done
    fi
    if [[ "${STUB_CLEANUP_MODE:-}" == "compose-leader-exits" ]]; then
      echo "$$" >> "$STUB_STATE_DIR/docker-leader-exits-pids"
      (trap '' INT TERM; while true; do sleep 1; done) &
      echo "$!" >> "$STUB_STATE_DIR/docker-leader-exits-pids"
      trap 'exit 0' TERM
      while true; do sleep 1; done
    fi
    if [[ "${STUB_CLEANUP_MODE:-}" == "lock-failure" ]]; then
      for lock_path in "$REFERENCE_LOCK_ROOT"/moduvera-reference-*.lock; do
        [[ -d "$lock_path" ]] || continue
        : > "$lock_path/injected-cleanup-blocker"
        break
      done
    fi
    : > "$STUB_STATE_DIR/compose-down-finished"
  fi
fi
exit 0
SH

cat > "$STUB_BIN/curl" <<'SH'
#!/usr/bin/env bash
exit 0
SH

cat > "$STUB_BIN/uv" <<'SH'
#!/usr/bin/env bash
if [[ "${STUB_PRIMARY_FAILURE:-0}" == "1" ]]; then
  "$REAL_PYTHON" -c 'import time; print(time.monotonic_ns())' > "$STUB_STATE_DIR/cleanup-start"
  echo "injected public-contract failure" >&2
  exit 23
fi
echo "reference acceptance: PASS (topology=${REFERENCE_TOPOLOGY}; stub contract)"
SH

cat > "$STUB_BIN/java" <<'SH'
#!/usr/bin/env bash
if [[ "${STUB_JAVA_MODE:-fast}" == "slow" ]]; then
  echo "$$" >> "$STUB_STATE_DIR/java-pids"
  trap '' INT TERM
  while true; do sleep 1; done
fi
exit 0
SH

cat > "$STUB_BIN/python3" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == *"/preflight_ports.py" ]]; then
  : > "$STUB_STATE_DIR/preflight-mocked"
  exit 0
fi
if [[ "${1:-}" == *"/blackbox.py" ]]; then
  if [[ "${2:-}" == "resume" ]]; then
    "$REAL_PYTHON" -c 'import time; print(time.monotonic_ns())' > "$STUB_STATE_DIR/cleanup-start"
  fi
  exit 0
fi
exec "$REAL_PYTHON" "$@"
SH
chmod +x "$STUB_BIN"/*

run_case() {
  local name="$1" cleanup_mode="$2" primary_failure="$3" java_mode="${4:-fast}"
  local case_dir="$TEST_DIR/$name" status start_seconds
  mkdir "$case_dir" "$case_dir/locks" "$case_dir/state"
  start_seconds=$SECONDS
  set +e
  PATH="$STUB_BIN:$PATH" REAL_PYTHON="$REAL_PYTHON" JAVA_HOME="$case_dir/no-java" \
    STUB_STATE_DIR="$case_dir/state" STUB_CLEANUP_MODE="$cleanup_mode" \
    STUB_PRIMARY_FAILURE="$primary_failure" STUB_JAVA_MODE="$java_mode" RUN_SLOT=50 \
    REFERENCE_PREFLIGHT_ONLY=0 REFERENCE_KEEP_RUNNING=0 \
    REFERENCE_APP_STOP_TIMEOUT_SECONDS=1 REFERENCE_DIAGNOSTICS_TIMEOUT_SECONDS=1 \
    REFERENCE_COMPOSE_DOWN_TIMEOUT_SECONDS=2 \
    REFERENCE_STDOUT_EVIDENCE_DIR="$case_dir/stdout" \
    REFERENCE_LOCK_ROOT="$case_dir/locks" REFERENCE_PORT_MANIFEST="$case_dir/manifest.json" \
    "$HARNESS_DIR/run-topology.sh" microservices >"$case_dir/out" 2>"$case_dir/err"
  status=$?
  set -e
  printf '%s\n' "$status" > "$case_dir/status"
  printf '%s\n' "$((SECONDS - start_seconds))" > "$case_dir/elapsed"
  # The final stub operation marks entry into teardown; startup/lock acquisition is not cleanup.
  "$REAL_PYTHON" - "$case_dir/state/cleanup-start" > "$case_dir/cleanup-elapsed-ms" <<'PY'
import pathlib
import sys
import time

started = int(pathlib.Path(sys.argv[1]).read_text())
print((time.monotonic_ns() - started) // 1_000_000)
PY
}

run_case success "" 0
[[ "$(<"$TEST_DIR/success/status")" == "0" ]] || fail "successful cleanup changed the run status"
[[ -z "$(find "$TEST_DIR/success/locks" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
  || fail "successful cleanup left run locks"
[[ -f "$TEST_DIR/success/state/preflight-mocked" ]] \
  || fail "cleanup fixture did not use its isolated preflight mock"
for app in gateway identity catalog order inventory; do
  [[ -f "$TEST_DIR/success/stdout/$app.log" ]] \
    || fail "cleanup removed $app stdout before retaining requested evidence"
done

run_case compose-failure compose-failure 0
[[ "$(<"$TEST_DIR/compose-failure/status")" == "70" ]] \
  || fail "compose cleanup failure returned $(<"$TEST_DIR/compose-failure/status") instead of 70"
grep -F "Reference cleanup failure: docker compose down failed" \
  "$TEST_DIR/compose-failure/err" >/dev/null \
  || fail "compose cleanup failure evidence is missing"

run_case compose-hang compose-hang 0
[[ "$(<"$TEST_DIR/compose-hang/status")" == "70" ]] \
  || fail "hung compose cleanup returned $(<"$TEST_DIR/compose-hang/status") instead of 70"
(( $(<"$TEST_DIR/compose-hang/cleanup-elapsed-ms") < 6000 )) \
  || fail "hung compose cleanup exceeded its bounded wall-clock deadline"
grep -F "Reference cleanup failure: docker compose down failed" \
  "$TEST_DIR/compose-hang/err" >/dev/null \
  || fail "hung compose cleanup failure evidence is missing"
grep -F "Reference cleanup detail: compose down exceeded the 2s wall-clock deadline" \
  "$TEST_DIR/compose-hang/err" >/dev/null \
  || fail "hung compose cleanup timeout evidence is missing"
[[ -f "$TEST_DIR/compose-hang/state/compose-down-started" ]] \
  || fail "hung compose cleanup did not reach docker compose down"
[[ -z "$(find "$TEST_DIR/compose-hang/locks" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
  || fail "hung compose cleanup did not release owned locks"
HUNG_RUN_DIR="$($REAL_PYTHON - "$TEST_DIR/compose-hang/manifest.json" <<'PY'
import json
import pathlib
import sys

print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["resources"]["tempDirectory"])
PY
)"
[[ ! -e "$HUNG_RUN_DIR" ]] || fail "hung compose cleanup leaked temporary run directory"
while IFS= read -r pid; do
  for _ in {1..40}; do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 0.05
  done
  if kill -0 "$pid" 2>/dev/null; then
    fail "hung compose process $pid survived watchdog escalation"
  fi
done < "$TEST_DIR/compose-hang/state/docker-pids"

run_case compose-leader-exits compose-leader-exits 0
[[ "$(<"$TEST_DIR/compose-leader-exits/status")" == "70" ]] \
  || fail "compose descendant escape returned $(<"$TEST_DIR/compose-leader-exits/status") instead of 70"
(( $(<"$TEST_DIR/compose-leader-exits/cleanup-elapsed-ms") < 6000 )) \
  || fail "compose descendant escape exceeded its bounded wall-clock deadline"
grep -F "Reference cleanup detail: compose down exceeded the 2s wall-clock deadline" \
  "$TEST_DIR/compose-leader-exits/err" >/dev/null \
  || fail "compose descendant escape timeout evidence is missing"
[[ -z "$(find "$TEST_DIR/compose-leader-exits/locks" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
  || fail "compose descendant escape did not release owned locks"
LEADER_EXIT_RUN_DIR="$($REAL_PYTHON - "$TEST_DIR/compose-leader-exits/manifest.json" <<'PY'
import json
import pathlib
import sys

print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["resources"]["tempDirectory"])
PY
)"
[[ ! -e "$LEADER_EXIT_RUN_DIR" ]] \
  || fail "compose descendant escape leaked temporary run directory"
while IFS= read -r pid; do
  for _ in {1..40}; do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 0.05
  done
  if kill -0 "$pid" 2>/dev/null; then
    fail "compose process $pid survived after its group leader exited on TERM"
  fi
done < "$TEST_DIR/compose-leader-exits/state/docker-leader-exits-pids"

EARLY_LEADER="$TEST_DIR/early-leader.py"
cat > "$EARLY_LEADER" <<'PY'
#!/usr/bin/env python3
import os
import pathlib
import signal
import time

descendant = os.fork()
if descendant == 0:
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    marker = os.environ.get("EARLY_DESCENDANT_TERM_MARKER")
    if marker:
        def record_term(_received, _frame):
            pathlib.Path(marker).write_text("TERM", encoding="ascii")
        signal.signal(signal.SIGTERM, record_term)
    else:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    while True:
        time.sleep(1)
pathlib.Path(os.environ["EARLY_DESCENDANT_PID_FILE"]).write_text(
    str(descendant), encoding="ascii"
)
PY
chmod +x "$EARLY_LEADER"
EARLY_DESCENDANT_PID_FILE="$TEST_DIR/early-descendant-pid" \
  python3 "$HARNESS_DIR/bounded_process.py" 2 -- "$EARLY_LEADER" \
  >"$TEST_DIR/early-leader.out" 2>"$TEST_DIR/early-leader.err" \
  || fail "bounded wrapper did not preserve a successful leader status after draining its group"
EARLY_DESCENDANT_PID="$(<"$TEST_DIR/early-descendant-pid")"
for _ in {1..40}; do
  if ! kill -0 "$EARLY_DESCENDANT_PID" 2>/dev/null; then break; fi
  sleep 0.05
done
if kill -0 "$EARLY_DESCENDANT_PID" 2>/dev/null; then
  fail "normal-exit command descendant survived bounded process-group drain"
fi

run_bounded_drain_signal_case() {
  local name="$1" signal_name="$2" expected_status="$3"
  local case_dir="$TEST_DIR/$name" status descendant_pid
  mkdir "$case_dir"
  EARLY_DESCENDANT_PID_FILE="$case_dir/descendant-pid" \
    EARLY_DESCENDANT_TERM_MARKER="$case_dir/drain-term-seen" \
    python3 "$HARNESS_DIR/bounded_process.py" 2 -- "$EARLY_LEADER" \
      >"$case_dir/out" 2>"$case_dir/err" &
  BOUNDED_PID=$!
  for _ in {1..100}; do
    [[ -f "$case_dir/drain-term-seen" ]] && break
    sleep 0.01
  done
  [[ -f "$case_dir/drain-term-seen" ]] \
    || fail "$signal_name bounded-process test did not enter residual-group drain"
  kill -s "$signal_name" "$BOUNDED_PID"
  set +e
  wait "$BOUNDED_PID"
  status=$?
  set -e
  BOUNDED_PID=""
  [[ $status -eq $expected_status ]] \
    || fail "$signal_name during bounded-process drain returned $status instead of $expected_status"
  descendant_pid="$(<"$case_dir/descendant-pid")"
  for _ in {1..40}; do kill -0 "$descendant_pid" 2>/dev/null || break; sleep 0.05; done
  if kill -0 "$descendant_pid" 2>/dev/null; then
    fail "$signal_name bounded-process drain left descendant $descendant_pid alive"
  fi
}

run_bounded_drain_signal_case bounded-drain-term TERM 143
run_bounded_drain_signal_case bounded-drain-int INT 130

run_case diagnostics-hang diagnostics-hang 1
[[ "$(<"$TEST_DIR/diagnostics-hang/status")" == "23" ]] \
  || fail "hung diagnostics replaced the primary failure status"
(( $(<"$TEST_DIR/diagnostics-hang/cleanup-elapsed-ms") < 6000 )) \
  || fail "hung diagnostics exceeded the bounded cleanup budget"
grep -F "Reference diagnostics exceeded the 1s wall-clock deadline" \
  "$TEST_DIR/diagnostics-hang/err" >/dev/null \
  || fail "hung diagnostics timeout evidence is missing"
[[ -f "$TEST_DIR/diagnostics-hang/state/compose-down-finished" ]] \
  || fail "hung diagnostics prevented docker compose down"
[[ -z "$(find "$TEST_DIR/diagnostics-hang/locks" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
  || fail "hung diagnostics prevented owned-lock release"
DIAGNOSTICS_RUN_DIR="$($REAL_PYTHON - "$TEST_DIR/diagnostics-hang/manifest.json" <<'PY'
import json
import pathlib
import sys

print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["resources"]["tempDirectory"])
PY
)"
[[ ! -e "$DIAGNOSTICS_RUN_DIR" ]] || fail "hung diagnostics leaked temporary run directory"
while IFS= read -r pid; do
  for _ in {1..40}; do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 0.05
  done
  if kill -0 "$pid" 2>/dev/null; then
    fail "hung diagnostics process $pid survived watchdog escalation"
  fi
done < "$TEST_DIR/diagnostics-hang/state/diagnostics-pids"

run_case lock-failure lock-failure 0
[[ "$(<"$TEST_DIR/lock-failure/status")" == "70" ]] \
  || fail "lock cleanup failure returned $(<"$TEST_DIR/lock-failure/status") instead of 70"
grep -F "Reference cleanup failure: one or more owned run locks could not be released" \
  "$TEST_DIR/lock-failure/err" >/dev/null \
  || fail "lock cleanup failure evidence is missing"

run_case primary-preserved compose-failure 1
[[ "$(<"$TEST_DIR/primary-preserved/status")" == "23" ]] \
  || fail "cleanup failure replaced the primary failure status"
grep -F "injected public-contract failure" "$TEST_DIR/primary-preserved/err" >/dev/null \
  || fail "primary failure evidence is missing"
grep -F "Reference cleanup failure: docker compose down failed" \
  "$TEST_DIR/primary-preserved/err" >/dev/null \
  || fail "secondary cleanup failure evidence is missing"

run_case slow-jvm-cleanup "" 0 slow
[[ "$(<"$TEST_DIR/slow-jvm-cleanup/status")" == "0" ]] \
  || fail "slow JVM cleanup did not complete successfully"
(( $(<"$TEST_DIR/slow-jvm-cleanup/cleanup-elapsed-ms") < 6000 )) \
  || fail "slow JVMs were stopped sequentially instead of under one shared deadline"
[[ -f "$TEST_DIR/slow-jvm-cleanup/state/compose-down-finished" ]] \
  || fail "slow JVM cleanup did not reach docker compose down"
while IFS= read -r pid; do
  if kill -0 "$pid" 2>/dev/null; then
    fail "slow JVM process $pid survived cleanup"
  fi
done < "$TEST_DIR/slow-jvm-cleanup/state/java-pids"

INVALID_TMP="$TEST_DIR/invalid-timeout-tmp"
mkdir "$INVALID_TMP"
set +e
TMPDIR="$INVALID_TMP" RUN_SLOT=50 REFERENCE_APP_STOP_TIMEOUT_SECONDS=invalid \
  REFERENCE_LOCK_ROOT="$TEST_DIR/success/locks" \
  "$HARNESS_DIR/run-topology.sh" microservices \
  >"$TEST_DIR/invalid-timeout.out" 2>"$TEST_DIR/invalid-timeout.err"
INVALID_TIMEOUT_STATUS=$?
set -e
[[ "$INVALID_TIMEOUT_STATUS" == "64" ]] \
  || fail "invalid timeout returned $INVALID_TIMEOUT_STATUS instead of 64"
[[ -z "$(find "$INVALID_TMP" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
  || fail "invalid timeout leaked a temporary run directory"
grep -F "Invalid REFERENCE_APP_STOP_TIMEOUT_SECONDS 'invalid'" \
  "$TEST_DIR/invalid-timeout.err" >/dev/null \
  || fail "invalid timeout diagnostic is missing"

echo "Reference cleanup tests: PASS"
