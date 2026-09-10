#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
# shellcheck source=agent-runtime.sh
source "$SCRIPT_DIR/agent-runtime.sh"
EVIDENCE_DIR="${MODUVERA_TASK_EVIDENCE_DIR:?external evidence directory required}"
AGENT="${MODUVERA_OTEL_JAVAAGENT:?locked Agent required}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-$SCRIPT_DIR/agent-extension/target/moduvera-governed-otel-agent-extension-0.1.0-SNAPSHOT.jar}"
FIXTURE="$SCRIPT_DIR/logging-fixture/target/moduvera-governed-logging-fixture-0.1.0-SNAPSHOT.jar"
RECEIVER_PID=""
mkdir -p "$EVIDENCE_DIR"
cleanup() {
  local result=$?
  trap - EXIT INT TERM
  if [[ -n "$RECEIVER_PID" ]]; then
    kill -TERM "$RECEIVER_PID" 2>/dev/null || true
    wait "$RECEIVER_PID" 2>/dev/null || true
  fi
  exit "$result"
}
trap cleanup EXIT INT TERM
: > "$EVIDENCE_DIR/task-otlp.port"
printf '{}\n' > "$EVIDENCE_DIR/task-sentinels.json"
python3 "$SCRIPT_DIR/otlp_receiver.py" \
  --port-file "$EVIDENCE_DIR/task-otlp.port" \
  --output "$EVIDENCE_DIR/task-spans.jsonl" \
  --status "$EVIDENCE_DIR/task-receiver-status.json" \
  --sentinels "$EVIDENCE_DIR/task-sentinels.json" \
  > "$EVIDENCE_DIR/task-receiver.log" 2>&1 &
RECEIVER_PID=$!
deadline=$((SECONDS + 10))
until [[ -s "$EVIDENCE_DIR/task-otlp.port" ]]; do
  kill -0 "$RECEIVER_PID" 2>/dev/null || { cat "$EVIDENCE_DIR/task-receiver.log" >&2; exit 1; }
  (( SECONDS < deadline )) || { echo "task OTLP receiver did not start" >&2; exit 1; }
  sleep 0.1
done
REFERENCE_OTLP_TRACES_ENDPOINT="http://127.0.0.1:$(<"$EVIDENCE_DIR/task-otlp.port")/v1/traces"
REFERENCE_JAVA_TOOL_OPTIONS=""
governed_agent_preflight "$AGENT" "$EXTENSION"
OPTIONS="$(governed_agent_java_options task-fixture "$AGENT" "$EXTENSION") -Dmoduvera.fixture=tasks"
set +e
JAVA_TOOL_OPTIONS="$OPTIONS" java -jar "$FIXTURE" \
  --spring.main.banner-mode=off \
  --spring.application.name=task-fixture \
  --logging.structured.ecs.service.version=0.1.0-verification \
  --logging.structured.ecs.service.environment=verification \
  > "$EVIDENCE_DIR/task-fixture.stdout.jsonl" 2> "$EVIDENCE_DIR/task-fixture.stderr.log"
result=$?
set -e
printf '%s\n' "$result" > "$EVIDENCE_DIR/task-fixture.exit"
(( result == 0 )) || { cat "$EVIDENCE_DIR/task-fixture.stderr.log" >&2; exit "$result"; }
python3 "$SCRIPT_DIR/analyze_task_fixture.py" "$EVIDENCE_DIR"
