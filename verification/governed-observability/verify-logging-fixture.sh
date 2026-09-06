#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
# shellcheck source=agent-runtime.sh
source "$SCRIPT_DIR/agent-runtime.sh"
AGENT="${MODUVERA_OTEL_JAVAAGENT:-}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-$SCRIPT_DIR/agent-extension/target/moduvera-governed-otel-agent-extension-0.1.0-SNAPSHOT.jar}"
FIXTURE_JAR="${MODUVERA_LOGGING_FIXTURE_JAR:-$SCRIPT_DIR/logging-fixture/target/moduvera-governed-logging-fixture-0.1.0-SNAPSHOT.jar}"
EVIDENCE_DIR="${MODUVERA_LOGGING_EVIDENCE_DIR:-}"
APP_PID=""
RECEIVER_PID=""

[[ -n "$EVIDENCE_DIR" && -f "$AGENT" && -f "$EXTENSION" && -f "$FIXTURE_JAR" ]] \
  || { echo "logging fixture requires evidence directory and built runtime artifacts" >&2; exit 64; }
mkdir -p "$EVIDENCE_DIR"

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill -TERM "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [[ -n "$RECEIVER_PID" ]] && kill -0 "$RECEIVER_PID" 2>/dev/null; then
    kill -TERM "$RECEIVER_PID" 2>/dev/null || true
    wait "$RECEIVER_PID" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

python3 - "$EVIDENCE_DIR/logging-sentinels.json" <<'PY'
import json
from pathlib import Path
import secrets
import sys

prefix = secrets.token_hex(16)
Path(sys.argv[1]).write_text(json.dumps({
    "credential": "credential-" + prefix,
    "query": "query-" + prefix,
    "sql": "sql-" + prefix,
}, sort_keys=True) + "\n", encoding="utf-8")
PY
FIXTURE_SECRET="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["credential"])' "$EVIDENCE_DIR/logging-sentinels.json")"
FIXTURE_QUERY_SENTINEL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["query"])' "$EVIDENCE_DIR/logging-sentinels.json")"
FIXTURE_SQL_SENTINEL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sql"])' "$EVIDENCE_DIR/logging-sentinels.json")"

: > "$EVIDENCE_DIR/logging-otlp.port"
: > "$EVIDENCE_DIR/logging-fixture.port"
python3 "$SCRIPT_DIR/otlp_receiver.py" \
  --port-file "$EVIDENCE_DIR/logging-otlp.port" \
  --output "$EVIDENCE_DIR/logging-spans.jsonl" \
  --status "$EVIDENCE_DIR/logging-receiver-status.json" \
  --sentinels "$EVIDENCE_DIR/logging-sentinels.json" \
  >"$EVIDENCE_DIR/logging-receiver.log" 2>&1 &
RECEIVER_PID=$!
deadline=$((SECONDS + 10))
until [[ -s "$EVIDENCE_DIR/logging-otlp.port" ]]; do
  kill -0 "$RECEIVER_PID" 2>/dev/null || { cat "$EVIDENCE_DIR/logging-receiver.log" >&2; exit 1; }
  (( SECONDS < deadline )) || { echo "logging OTLP receiver did not start" >&2; exit 1; }
  sleep 0.1
done
OTLP_PORT="$(<"$EVIDENCE_DIR/logging-otlp.port")"
REFERENCE_JAVA_TOOL_OPTIONS=""
REFERENCE_OTLP_TRACES_ENDPOINT="http://127.0.0.1:$OTLP_PORT/v1/traces"
governed_agent_preflight "$AGENT" "$EXTENSION"
OPTIONS="$(governed_agent_java_options logging-fixture "$AGENT" "$EXTENSION")"

FIXTURE_PORT_FILE="$EVIDENCE_DIR/logging-fixture.port" \
FIXTURE_SECRET="$FIXTURE_SECRET" \
FIXTURE_QUERY_SENTINEL="$FIXTURE_QUERY_SENTINEL" \
FIXTURE_SQL_SENTINEL="$FIXTURE_SQL_SENTINEL" \
JAVA_TOOL_OPTIONS="$OPTIONS" \
  java -jar "$FIXTURE_JAR" \
  --spring.main.banner-mode=off \
  --spring.application.name=logging-fixture \
  --logging.structured.ecs.service.version=0.1.0-verification \
  --logging.structured.ecs.service.environment=verification \
  >"$EVIDENCE_DIR/logging-fixture.stdout.jsonl" \
  2>"$EVIDENCE_DIR/logging-fixture.stderr.log" &
APP_PID=$!
deadline=$((SECONDS + 20))
until [[ -s "$EVIDENCE_DIR/logging-fixture.port" ]]; do
  kill -0 "$APP_PID" 2>/dev/null || { cat "$EVIDENCE_DIR/logging-fixture.stderr.log" >&2; exit 1; }
  (( SECONDS < deadline )) || { cat "$EVIDENCE_DIR/logging-fixture.stderr.log" >&2; exit 1; }
  sleep 0.1
done
FIXTURE_PORT="$(<"$EVIDENCE_DIR/logging-fixture.port")"

curl --fail --silent --show-error --max-time 5 \
  -H 'traceparent: 00-11111111111111111111111111111111-2222222222222222-01' \
  "http://127.0.0.1:$FIXTURE_PORT/info" >/dev/null
curl --fail --silent --show-error --max-time 5 \
  -H 'traceparent: 00-33333333333333333333333333333333-4444444444444444-00' \
  "http://127.0.0.1:$FIXTURE_PORT/error" >/dev/null

deadline=$((SECONDS + 10))
until python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["spans"] >= 1' \
  "$EVIDENCE_DIR/logging-receiver-status.json" 2>/dev/null; do
  kill -0 "$APP_PID" 2>/dev/null || { cat "$EVIDENCE_DIR/logging-fixture.stderr.log" >&2; exit 1; }
  (( SECONDS < deadline )) || { echo "logging fixture span was not exported" >&2; exit 1; }
  sleep 0.1
done

python3 "$SCRIPT_DIR/analyze_logging_fixture.py" \
  --stdout "$EVIDENCE_DIR/logging-fixture.stdout.jsonl" \
  --stderr "$EVIDENCE_DIR/logging-fixture.stderr.log" \
  --spans "$EVIDENCE_DIR/logging-spans.jsonl" \
  --status "$EVIDENCE_DIR/logging-receiver-status.json" \
  --sentinels "$EVIDENCE_DIR/logging-sentinels.json"
