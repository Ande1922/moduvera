#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-port-plan-test.XXXXXX")"
LOCK_HOLDER_PID=""

cleanup() {
  if [[ -n "$LOCK_HOLDER_PID" ]]; then
    kill "$LOCK_HOLDER_PID" 2>/dev/null || true
    wait "$LOCK_HOLDER_PID" 2>/dev/null || true
  fi
  rm -rf "$TEST_DIR"
}
trap cleanup EXIT

stop_lock_holder() {
  if [[ -n "$LOCK_HOLDER_PID" ]]; then
    kill "$LOCK_HOLDER_PID" 2>/dev/null || true
    wait "$LOCK_HOLDER_PID" 2>/dev/null || true
    LOCK_HOLDER_PID=""
  fi
}

fail() {
  echo "port-plan test failed: $*" >&2
  exit 1
}

expect_failure() {
  local expected="$1"
  shift
  if "$@" >"$TEST_DIR/failure.out" 2>"$TEST_DIR/failure.err"; then
    fail "command unexpectedly passed: $*"
  fi
  grep -F "$expected" "$TEST_DIR/failure.err" >/dev/null || {
    sed -n '1,20p' "$TEST_DIR/failure.err" >&2
    fail "failure did not contain: $expected"
  }
}

RUN_SLOT=0 "$HARNESS_DIR/port-plan.sh" microservices "$TEST_DIR/slot-0.json" >/dev/null
RUN_SLOT=2 "$HARNESS_DIR/port-plan.sh" microservices "$TEST_DIR/slot-2.json" >/dev/null
RUN_SLOT=1 REFERENCE_DEBUG=1 \
  "$HARNESS_DIR/port-plan.sh" business-core-monolith "$TEST_DIR/slot-1-debug.json" >/dev/null
RUN_SLOT=0 REFERENCE_POSTGRES_PORT=07777 REFERENCE_KAFKA_PORT=08080 \
  "$HARNESS_DIR/port-plan.sh" business-core-monolith "$TEST_DIR/canonical-ports.json" >/dev/null

python3 - "$TEST_DIR/slot-0.json" "$TEST_DIR/slot-2.json" \
  "$TEST_DIR/slot-1-debug.json" "$TEST_DIR/canonical-ports.json" <<'PY'
import json
import pathlib
import sys

slot0, slot2, slot1_debug, canonical = (
    json.loads(pathlib.Path(path).read_text(encoding="utf-8")) for path in sys.argv[1:]
)

assert slot0["schemaVersion"] == 1
assert slot0["runSlot"] == 0
assert slot0["portStride"] == 100
assert slot0["ports"]["postgres"] == 55432
assert slot0["ports"]["kafka"] == 59092
assert slot0["ports"]["apps"]["gateway"] == {
    "application": 58080,
    "management": 58080,
}
assert set(slot0["ports"]["apps"]) == {
    "gateway", "identity", "catalog", "order", "inventory"
}

for dependency in ("postgres", "kafka"):
    assert slot2["ports"][dependency] - slot0["ports"][dependency] == 200
for app in slot0["ports"]["apps"]:
    assert (
        slot2["ports"]["apps"][app]["application"]
        - slot0["ports"]["apps"][app]["application"]
        == 200
    )
    assert (
        slot2["ports"]["apps"][app]["management"]
        == slot2["ports"]["apps"][app]["application"]
    )

assert slot1_debug["debugEnabled"] is True
assert set(slot1_debug["ports"]["apps"]) == {"gateway", "identity", "monolith"}
assert slot1_debug["ports"]["apps"]["gateway"] == {
    "application": 58180,
    "management": 58180,
    "debug": 50180,
}
assert canonical["ports"]["postgres"] == 7777
assert canonical["ports"]["kafka"] == 8080
PY

expect_failure "Invalid RUN_SLOT '-1'" \
  env RUN_SLOT=-1 "$HARNESS_DIR/port-plan.sh" microservices "$TEST_DIR/invalid.json"
expect_failure "Invalid RUN_SLOT '01'" \
  env RUN_SLOT=01 "$HARNESS_DIR/port-plan.sh" microservices "$TEST_DIR/invalid.json"
expect_failure "Invalid kafka port '65592'" \
  env RUN_SLOT=65 "$HARNESS_DIR/port-plan.sh" microservices "$TEST_DIR/overflow.json"
expect_failure "Invalid RUN_SLOT '999999999999999999999999'; derived ports exceed 65535" \
  env RUN_SLOT=999999999999999999999999 \
  "$HARNESS_DIR/port-plan.sh" microservices "$TEST_DIR/overflow.json"

LOCK_ROOT="$TEST_DIR/locks"
mkdir "$LOCK_ROOT"

python3 - "$TEST_DIR/time-wait-port" <<'PY'
import pathlib
import socket
import sys

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", 0))
listener.listen()
port = listener.getsockname()[1]
client = socket.create_connection(("127.0.0.1", port))
connection, _ = listener.accept()
connection.shutdown(socket.SHUT_WR)
connection.close()
client.recv(1)
client.close()
listener.close()
pathlib.Path(sys.argv[1]).write_text(str(port), encoding="ascii")
PY
TIME_WAIT_PORT="$(<"$TEST_DIR/time-wait-port")"
python3 "$HARNESS_DIR/preflight_ports.py" "time-wait=$TIME_WAIT_PORT" \
  || fail "SO_REUSEADDR-compatible TIME_WAIT port was reported unavailable"

python3 - "$TEST_DIR/occupied-port" <<'PY' &
import pathlib
import socket
import sys
import time

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("0.0.0.0", 0))
listener.listen()
pathlib.Path(sys.argv[1]).write_text(str(listener.getsockname()[1]), encoding="ascii")
time.sleep(30)
PY
PORT_HOLDER_PID=$!
for _ in {1..40}; do [[ -s "$TEST_DIR/occupied-port" ]] && break; sleep 0.05; done
[[ -s "$TEST_DIR/occupied-port" ]] || fail "occupied-port helper did not start"
OCCUPIED_PORT="$(<"$TEST_DIR/occupied-port")"
expect_failure "Required port is unavailable before startup: gateway=$OCCUPIED_PORT" \
  env RUN_SLOT=50 REFERENCE_GATEWAY_PORT="$OCCUPIED_PORT" REFERENCE_PREFLIGHT_ONLY=1 \
  REFERENCE_LOCK_ROOT="$LOCK_ROOT" \
  "$HARNESS_DIR/run-topology.sh" microservices
[[ -z "$(find "$LOCK_ROOT" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
  || fail "occupied-port failure did not release its owned locks"
kill "$PORT_HOLDER_PID" 2>/dev/null || true
wait "$PORT_HOLDER_PID" 2>/dev/null || true

(
  # shellcheck source=../port-plan.sh
  source "$HARNESS_DIR/port-plan.sh"
  RUN_SLOT=7
  REFERENCE_LOCK_ROOT="$LOCK_ROOT"
  reference_configure_port_plan microservices
  reference_acquire_run_locks
  trap reference_release_run_locks EXIT
  sleep 30
) &
LOCK_HOLDER_PID=$!
for _ in {1..40}; do [[ -d "$LOCK_ROOT/moduvera-reference-slot-7.lock" ]] && break; sleep 0.05; done
[[ -d "$LOCK_ROOT/moduvera-reference-slot-7.lock" ]] || fail "slot-lock helper did not start"
expect_failure "Reference run slot 7 lock is active" bash -c '
  set -euo pipefail
  source "$1"
  RUN_SLOT=7
  REFERENCE_LOCK_ROOT="$2"
  reference_configure_port_plan microservices
  trap reference_release_run_locks EXIT
  reference_acquire_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT"
[[ -d "$LOCK_ROOT/moduvera-reference-slot-7.lock" ]] \
  || fail "failed acquisition removed the active slot lock"
stop_lock_holder

(
  # shellcheck source=../port-plan.sh
  source "$HARNESS_DIR/port-plan.sh"
  RUN_SLOT=18
  REFERENCE_LOCK_ROOT="$LOCK_ROOT"
  reference_configure_port_plan microservices
  reference_acquire_run_locks
  trap reference_release_run_locks EXIT
  sleep 30
) &
LOCK_HOLDER_PID=$!
for _ in {1..40}; do [[ -d "$LOCK_ROOT/moduvera-reference-slot-18.lock" ]] && break; sleep 0.05; done
[[ -d "$LOCK_ROOT/moduvera-reference-slot-18.lock" ]] || fail "unrelated-slot holder did not start"
bash -c '
  set -euo pipefail
  source "$1"
  RUN_SLOT=19
  REFERENCE_LOCK_ROOT="$2"
  reference_configure_port_plan business-core-monolith
  reference_acquire_run_locks
  reference_release_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT"
[[ -d "$LOCK_ROOT/moduvera-reference-slot-18.lock" ]] \
  || fail "verification of another slot disturbed the active unrelated slot"
stop_lock_holder

(
  # shellcheck source=../port-plan.sh
  source "$HARNESS_DIR/port-plan.sh"
  RUN_SLOT=8
  REFERENCE_GATEWAY_PORT=07777
  REFERENCE_LOCK_ROOT="$LOCK_ROOT"
  reference_configure_port_plan microservices
  reference_acquire_run_locks
  trap reference_release_run_locks EXIT
  sleep 30
) &
LOCK_HOLDER_PID=$!
for _ in {1..40}; do [[ -d "$LOCK_ROOT/moduvera-reference-port-7777.lock" ]] && break; sleep 0.05; done
[[ -d "$LOCK_ROOT/moduvera-reference-port-7777.lock" ]] || fail "canonical port-lock helper did not start"
expect_failure "Required host port 7777 lock is active" bash -c '
  set -euo pipefail
  source "$1"
  RUN_SLOT=9
  REFERENCE_GATEWAY_PORT=7777
  REFERENCE_LOCK_ROOT="$2"
  reference_configure_port_plan microservices
  trap reference_release_run_locks EXIT
  reference_acquire_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT"
[[ -d "$LOCK_ROOT/moduvera-reference-port-7777.lock" ]] \
  || fail "equivalent cross-slot collision removed the active canonical port lock"
stop_lock_holder

STALE_LOCK="$LOCK_ROOT/moduvera-reference-slot-11.lock"
mkdir "$STALE_LOCK"
printf '%s\n' '999999' > "$STALE_LOCK/pid"
bash -c '
  set -euo pipefail
  source "$1"
  RUN_SLOT=11
  REFERENCE_LOCK_ROOT="$2"
  REFERENCE_LOCK_OWNER=recovery-owner
  reference_configure_port_plan business-core-monolith
  reference_acquire_run_locks
  grep -F ":recovery-owner" "$2/moduvera-reference-slot-11.lock/owner" >/dev/null
  reference_release_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT" 2>"$TEST_DIR/stale-recovery.err"
grep -F "Reclaiming stale Reference run slot 11 lock (dead pid 999999)" \
  "$TEST_DIR/stale-recovery.err" >/dev/null || fail "stale lock was not diagnosed"
[[ ! -e "$STALE_LOCK" ]] || fail "recovered lock was not released"

REUSED_PID_LOCK="$LOCK_ROOT/moduvera-reference-pid-reuse.lock"
mkdir "$REUSED_PID_LOCK"
printf '%s:%064d:%s\n' "$$" 0 reused-owner > "$REUSED_PID_LOCK/owner"
bash -c '
  set -euo pipefail
  source "$1"
  REFERENCE_LOCK_ROOT="$2"
  REFERENCE_LOCK_OWNER=recovery-owner
  reference_prepare_locking
  reference_acquire_named_lock pid-reuse "PID reuse"
  reference_release_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT" 2>"$TEST_DIR/pid-reuse.err"
grep -F "Reclaiming stale PID reuse lock (birth identity does not match live pid $$)" \
  "$TEST_DIR/pid-reuse.err" >/dev/null || fail "PID reuse stale lock was not diagnosed"
[[ ! -e "$REUSED_PID_LOCK" ]] || fail "PID reuse stale lock was not safely reclaimed"

MISSING_OWNER_LOCK="$LOCK_ROOT/moduvera-reference-slot-12.lock"
mkdir "$MISSING_OWNER_LOCK"
expect_failure "lock has missing ownership metadata; refusing to reclaim" bash -c '
  set -euo pipefail
  source "$1"
  RUN_SLOT=12
  REFERENCE_LOCK_ROOT="$2"
  reference_configure_port_plan business-core-monolith
  trap reference_release_run_locks EXIT
  reference_acquire_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT"
rmdir "$MISSING_OWNER_LOCK"

expect_failure "Reference lock root is missing" bash -c '
  set -euo pipefail
  source "$1"
  RUN_SLOT=13
  REFERENCE_LOCK_ROOT="$2/missing-lock-root"
  reference_configure_port_plan business-core-monolith
  reference_acquire_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT"

UNWRITABLE_LOCK_ROOT="$TEST_DIR/unwritable-lock-root"
mkdir "$UNWRITABLE_LOCK_ROOT"
chmod 500 "$UNWRITABLE_LOCK_ROOT"
if [[ -w "$UNWRITABLE_LOCK_ROOT" ]]; then
  [[ "$(id -u)" == "0" ]] \
    || fail "chmod 500 lock root remained writable for a non-root test process"
  echo "Skipping mode-bit unwritable assertion for root test process"
else
  expect_failure "Reference lock root is not writable" bash -c '
    set -euo pipefail
    source "$1"
    RUN_SLOT=14
    REFERENCE_LOCK_ROOT="$2"
    reference_configure_port_plan business-core-monolith
    reference_acquire_run_locks
  ' bash "$HARNESS_DIR/port-plan.sh" "$UNWRITABLE_LOCK_ROOT"
fi
chmod 700 "$UNWRITABLE_LOCK_ROOT"

expect_failure "Reference lock ownership changed; refusing to release" bash -c '
  set -euo pipefail
  source "$1"
  REFERENCE_LOCK_ROOT="$2"
  REFERENCE_LOCK_OWNER=original-owner
  reference_prepare_locking
  reference_acquire_named_lock ownership-test "Ownership test"
  printf "%s\n" "$$:replacement-owner" > "$2/moduvera-reference-ownership-test.lock/owner"
  reference_release_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT"
[[ -d "$LOCK_ROOT/moduvera-reference-ownership-test.lock" ]] \
  || fail "ownership-safe cleanup removed a lock owned by another run"
rm -f "$LOCK_ROOT/moduvera-reference-ownership-test.lock/owner"
rmdir "$LOCK_ROOT/moduvera-reference-ownership-test.lock"

SYMLINK_TARGET="$TEST_DIR/symlink-target"
mkdir "$SYMLINK_TARGET"
printf '%s\n' 'external-sentinel' > "$SYMLINK_TARGET/sentinel"
ln -s "$SYMLINK_TARGET" "$LOCK_ROOT/moduvera-reference-planted.lock"
expect_failure "lock path is an unsafe symlink; refusing to use" bash -c '
  set -euo pipefail
  source "$1"
  REFERENCE_LOCK_ROOT="$2"
  reference_prepare_locking
  reference_acquire_named_lock planted "Planted"
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT"
[[ "$(<"$SYMLINK_TARGET/sentinel")" == "external-sentinel" ]] \
  || fail "planted lock symlink modified its external target"
rm "$LOCK_ROOT/moduvera-reference-planted.lock"

OWNER_SYMLINK_LOCK="$LOCK_ROOT/moduvera-reference-owner-symlink.lock"
mkdir "$OWNER_SYMLINK_LOCK"
printf '%s\n' '999999:external-owner' > "$SYMLINK_TARGET/external-owner"
ln -s "$SYMLINK_TARGET/external-owner" "$OWNER_SYMLINK_LOCK/owner"
expect_failure "lock has unsafe symlink ownership metadata; refusing to reclaim" bash -c '
  set -euo pipefail
  source "$1"
  REFERENCE_LOCK_ROOT="$2"
  reference_prepare_locking
  reference_acquire_named_lock owner-symlink "Owner symlink"
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT"
[[ "$(<"$SYMLINK_TARGET/external-owner")" == "999999:external-owner" ]] \
  || fail "owner symlink modified its external target"
[[ -L "$OWNER_SYMLINK_LOCK/owner" ]] || fail "unsafe owner symlink was deleted"
rm "$OWNER_SYMLINK_LOCK/owner"
rmdir "$OWNER_SYMLINK_LOCK"

RACE_LOCK="$LOCK_ROOT/moduvera-reference-tombstone-race.lock"
RACE_TOMBSTONE="$RACE_LOCK.stale.race-owner.1"
mkdir "$RACE_LOCK"
printf '%s\n' '999999:dead-owner' > "$RACE_LOCK/owner"
ln -s "$SYMLINK_TARGET" "$RACE_TOMBSTONE"
bash -c '
  set -euo pipefail
  source "$1"
  REFERENCE_LOCK_ROOT="$2"
  REFERENCE_LOCK_OWNER=race-owner
  reference_prepare_locking
  reference_acquire_named_lock tombstone-race "Tombstone race"
  reference_release_run_locks
' bash "$HARNESS_DIR/port-plan.sh" "$LOCK_ROOT" 2>"$TEST_DIR/tombstone-race.err"
grep -F "Reclaiming stale Tombstone race lock (dead pid 999999)" \
  "$TEST_DIR/tombstone-race.err" >/dev/null || fail "tombstone race did not recover safely"
[[ "$(<"$SYMLINK_TARGET/sentinel")" == "external-sentinel" ]] \
  || fail "tombstone race modified its external target"
[[ -L "$RACE_TOMBSTONE" ]] || fail "pre-existing tombstone symlink was clobbered"
rm "$RACE_TOMBSTONE"

LOCK_LINE="$(grep -n 'reference_acquire_run_locks' "$HARNESS_DIR/run-topology.sh" | cut -d: -f1)"
PREFLIGHT_LINE="$(grep -n 'reference_preflight_ports' "$HARNESS_DIR/run-topology.sh" | tail -1 | cut -d: -f1)"
COMPOSE_UP_LINE="$(grep -n 'compose up -d' "$HARNESS_DIR/run-topology.sh" | cut -d: -f1)"
(( LOCK_LINE < PREFLIGHT_LINE )) || fail "host-port locks do not precede the advisory bind probe"
(( PREFLIGHT_LINE < COMPOSE_UP_LINE )) || fail "port preflight does not precede Compose startup"

grep -F 'address=127.0.0.1:$debug_port' "$HARNESS_DIR/run-topology.sh" >/dev/null \
  || fail "JDWP does not bind explicitly to loopback"
if grep -F 'address=*:$debug_port' "$HARNESS_DIR/run-topology.sh" >/dev/null; then
  fail "JDWP still binds to all interfaces"
fi

HARNESS_TEST_LINE="$(grep -n 'tests/test-port-plan.sh' "$HARNESS_DIR/verify.sh" | cut -d: -f1)"
TOPOLOGY_START_LINE="$(grep -n 'run-topology.sh' "$HARNESS_DIR/verify.sh" | head -1 | cut -d: -f1)"
(( HARNESS_TEST_LINE < TOPOLOGY_START_LINE )) \
  || fail "verify.sh does not run harness regression tests before topology startup"

KAFKA_PROBE='kafka-topics --bootstrap-server localhost:29092 --list'
grep -F "$KAFKA_PROBE" "$HARNESS_DIR/run-topology.sh" >/dev/null \
  || fail "harness Kafka readiness probe is missing"
grep -F "$KAFKA_PROBE" "$HARNESS_DIR/../compose/docker-compose.yml" >/dev/null \
  || fail "Compose Kafka healthcheck does not match the harness readiness probe"

echo "Reference port-plan tests: PASS"
