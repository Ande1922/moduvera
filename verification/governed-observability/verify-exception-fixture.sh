#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
# shellcheck source=agent-runtime.sh
source "$SCRIPT_DIR/agent-runtime.sh"
AGENT="${MODUVERA_OTEL_JAVAAGENT:-}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-}"
ENDPOINT="${REFERENCE_OTLP_TRACES_ENDPOINT:-}"
SENTINELS="${MODUVERA_OBSERVABILITY_SENTINELS:-}"
EVIDENCE_DIR="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:-}"
FIXTURE_CLASS=io.github.ande1922.moduvera.verification.otel.ExceptionServerFixture
TEST_CLASSES="$SCRIPT_DIR/agent-extension/target/test-classes"
SERVER_PID=""

[[ -f "$AGENT" && -f "$EXTENSION" && -f "$SENTINELS" && -d "$TEST_CLASSES" && -d "$EVIDENCE_DIR" ]] \
  || { echo "exception fixture inputs are missing" >&2; exit 64; }
REFERENCE_JAVA_TOOL_OPTIONS=""
REFERENCE_OTLP_TRACES_ENDPOINT="$ENDPOINT"
governed_agent_preflight "$AGENT" "$EXTENSION"

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill -TERM "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

QUERY_SENTINEL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["query"])' "$SENTINELS")"
SQL_SENTINEL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sql"])' "$SENTINELS")"
HEADER_SENTINEL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["header"])' "$SENTINELS")"
COMMAND_SENTINEL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["command"])' "$SENTINELS")"
BASELINE_SPANS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["spans"])' "$EVIDENCE_DIR/receiver-status.json")"
OPTIONS="$(governed_agent_java_options exception-fixture "$AGENT" "$EXTENSION")"
JAVA_TOOL_OPTIONS="$OPTIONS" FIXTURE_SQL_SENTINEL="$SQL_SENTINEL" \
  java -cp "$TEST_CLASSES" "$FIXTURE_CLASS" "$COMMAND_SENTINEL" \
  >"$EVIDENCE_DIR/exception-fixture.log" 2>&1 &
SERVER_PID=$!
deadline=$((SECONDS + 15))
until rg -q '^[0-9]+$' "$EVIDENCE_DIR/exception-fixture.log"; do
  kill -0 "$SERVER_PID" 2>/dev/null || { cat "$EVIDENCE_DIR/exception-fixture.log" >&2; exit 1; }
  (( SECONDS < deadline )) || { cat "$EVIDENCE_DIR/exception-fixture.log" >&2; exit 1; }
  sleep 0.1
done
SERVER_PORT="$(rg '^[0-9]+$' "$EVIDENCE_DIR/exception-fixture.log" | tail -n 1)"
curl --silent --max-time 3 --user-agent "$HEADER_SENTINEL" \
  "http://127.0.0.1:$SERVER_PORT/boom?probe=$QUERY_SENTINEL" >/dev/null || true
deadline=$((SECONDS + 10))
until python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["spans"] > int(sys.argv[2])' \
  "$EVIDENCE_DIR/receiver-status.json" "$BASELINE_SPANS" 2>/dev/null; do
  (( SECONDS < deadline )) || { echo "exception fixture span was not exported" >&2; exit 1; }
  sleep 0.1
done
kill -TERM "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""
grep -Fq 'Governed OpenTelemetry Agent extension handshake: PASS' "$EVIDENCE_DIR/exception-fixture.log"
python3 -c 'import json,sys; status=json.load(open(sys.argv[1])); assert status["sensitiveMatches"] == [], status' \
  "$EVIDENCE_DIR/receiver-status.json"
python3 - "$EVIDENCE_DIR/spans.jsonl" <<'PY'
import json
import sys

spans = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
matches = [
    span
    for span in spans
    if span.get("resource", {}).get("service.name") == "exception-fixture"
    and span.get("kind") == "SERVER"
    and span.get("attributes", {}).get("http.request.method") == "GET"
    and span.get("attributes", {}).get("url.path") == "/boom"
    and span.get("status", {}).get("code") == 2
]
assert len(matches) == 1, f"expected one sanitized real-Agent exception span, got {len(matches)}"
resource = matches[0]["resource"]
assert "process.command_args" not in resource
assert "process.command_line" not in resource
assert resource.get("telemetry.sdk.name") == "opentelemetry"
assert resource.get("telemetry.sdk.version") == "1.65.0"
PY
echo "Governed real Agent exception fixture: PASS"
