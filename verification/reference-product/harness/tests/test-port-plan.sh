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

python3 - "$TEST_DIR/slot-0.json" "$TEST_DIR/slot-2.json" "$TEST_DIR/slot-1-debug.json" <<'PY'
import json
import pathlib
import sys

slot0, slot2, slot1_debug = (
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

python3 - "$TEST_DIR/occupied-port" <<'PY' &
import pathlib
import socket
import sys
import time

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.bind(("127.0.0.1", 0))
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
  "$HARNESS_DIR/run-topology.sh" microservices
kill "$PORT_HOLDER_PID" 2>/dev/null || true
wait "$PORT_HOLDER_PID" 2>/dev/null || true

(
  # shellcheck source=../port-plan.sh
  source "$HARNESS_DIR/port-plan.sh"
  RUN_SLOT=7
  REFERENCE_SLOT_LOCK_ROOT="$TEST_DIR"
  reference_configure_port_plan microservices
  reference_acquire_slot
  sleep 30
) &
LOCK_HOLDER_PID=$!
for _ in {1..40}; do [[ -d "$TEST_DIR/moduvera-reference-slot-7.lock" ]] && break; sleep 0.05; done
[[ -d "$TEST_DIR/moduvera-reference-slot-7.lock" ]] || fail "slot-lock helper did not start"
expect_failure "Reference run slot 7 is already active" bash -c '
  set -euo pipefail
  source "$1"
  RUN_SLOT=7
  REFERENCE_SLOT_LOCK_ROOT="$2"
  reference_configure_port_plan microservices
  reference_acquire_slot
' bash "$HARNESS_DIR/port-plan.sh" "$TEST_DIR"
[[ -d "$TEST_DIR/moduvera-reference-slot-7.lock" ]] \
  || fail "failed acquisition removed the active slot lock"
kill "$LOCK_HOLDER_PID" 2>/dev/null || true
wait "$LOCK_HOLDER_PID" 2>/dev/null || true
LOCK_HOLDER_PID=""

PREFLIGHT_LINE="$(grep -n 'reference_preflight_ports' "$HARNESS_DIR/run-topology.sh" | tail -1 | cut -d: -f1)"
COMPOSE_UP_LINE="$(grep -n 'compose up -d' "$HARNESS_DIR/run-topology.sh" | cut -d: -f1)"
(( PREFLIGHT_LINE < COMPOSE_UP_LINE )) || fail "port preflight does not precede Compose startup"

KAFKA_PROBE='kafka-topics --bootstrap-server localhost:29092 --list'
grep -F "$KAFKA_PROBE" "$HARNESS_DIR/run-topology.sh" >/dev/null \
  || fail "harness Kafka readiness probe is missing"
grep -F "$KAFKA_PROBE" "$HARNESS_DIR/../compose/docker-compose.yml" >/dev/null \
  || fail "Compose Kafka healthcheck does not match the harness readiness probe"

echo "Reference port-plan tests: PASS"
