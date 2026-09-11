#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=agent-runtime.sh
source "$SCRIPT_DIR/agent-runtime.sh"
AGENT="${MODUVERA_OTEL_JAVAAGENT:-}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-}"
EVIDENCE_DIR="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:-}"
[[ -d "$EVIDENCE_DIR" ]] || { echo "Publication preparation fixture evidence directory is required" >&2; exit 64; }
governed_agent_preflight "$AGENT" "$EXTENSION" > "$EVIDENCE_DIR/preparation-agent-preflight.log"
OPTIONS="$(governed_agent_java_options preparation-ticket11 "$AGENT" "$EXTENSION") -Dmoduvera.test.agent=true"
cd "$PROJECT_ROOT"
set +e
./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=PublicationPreparationIT -Dfailsafe.failIfNoSpecifiedTests=false "-DargLine=$OPTIONS" \
  > "$EVIDENCE_DIR/preparation-agent.log" 2>&1
result=$?
set -e
printf '%s\n' "$result" > "$EVIDENCE_DIR/preparation-agent.exit"
[[ "$result" == 0 ]] || { tail -40 "$EVIDENCE_DIR/preparation-agent.log"; exit "$result"; }
rg -q 'Governed OpenTelemetry Agent extension handshake: PASS' "$EVIDENCE_DIR/preparation-agent.log"
echo "Publication preparation locked-Agent fixture: PASS"
