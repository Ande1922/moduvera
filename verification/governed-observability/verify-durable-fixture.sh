#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=agent-runtime.sh
source "$SCRIPT_DIR/agent-runtime.sh"
AGENT="${MODUVERA_OTEL_JAVAAGENT:-}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-}"
EVIDENCE_DIR="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:-}"
[[ -d "$EVIDENCE_DIR" ]] || { echo "Durable fixture evidence directory is required" >&2; exit 64; }
governed_agent_preflight "$AGENT" "$EXTENSION" > "$EVIDENCE_DIR/durable-agent-preflight.log"
OPTIONS="$(governed_agent_java_options durable-ticket10 "$AGENT" "$EXTENSION") -Dmoduvera.test.agent=true"
cd "$PROJECT_ROOT"
set +e
./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=DurableAppendIT -Dfailsafe.failIfNoSpecifiedTests=false "-DargLine=$OPTIONS" \
  > "$EVIDENCE_DIR/durable-agent.log" 2>&1
result=$?
set -e
printf '%s\n' "$result" > "$EVIDENCE_DIR/durable-agent.exit"
[[ "$result" == 0 ]] || { tail -40 "$EVIDENCE_DIR/durable-agent.log"; exit "$result"; }
rg -q 'Governed OpenTelemetry Agent extension handshake: PASS' "$EVIDENCE_DIR/durable-agent.log"
echo "Durable locked-Agent fixture: PASS"
