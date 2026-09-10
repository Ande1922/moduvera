#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=agent-runtime.sh
source "$SCRIPT_DIR/agent-runtime.sh"
AGENT="${MODUVERA_OTEL_JAVAAGENT:-}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-}"
EVIDENCE_DIR="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:-}"
[[ -d "$EVIDENCE_DIR" ]] || { echo "Servlet fixture evidence directory is required" >&2; exit 64; }
governed_agent_preflight "$AGENT" "$EXTENSION" > "$EVIDENCE_DIR/servlet-agent-preflight.log"
# Disable only the test HTTP client instrumentation, so its explicit W3C inputs reach the server unchanged.
OPTIONS="$(governed_agent_java_options servlet-ticket04 "$AGENT" "$EXTENSION") -Dotel.instrumentation.java-http-client.enabled=false -Dmoduvera.test.agent=true"
cd "$PROJECT_ROOT"
set +e
./mvnw -pl framework/starters/moduvera-web-spring-boot-starter,framework/starters/moduvera-auth-resource-server-autoconfigure \
  -am test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=ServletRequestDiagnosticsIT,HttpExecutionBoundaryIT -Dfailsafe.failIfNoSpecifiedTests=false \
  "-DargLine=$OPTIONS" > "$EVIDENCE_DIR/servlet-agent.log" 2>&1
result=$?
set -e
printf '%s\n' "$result" > "$EVIDENCE_DIR/servlet-agent.exit"
[[ "$result" == 0 ]] || { tail -40 "$EVIDENCE_DIR/servlet-agent.log"; exit "$result"; }
rg -q 'Governed OpenTelemetry Agent extension handshake: PASS' "$EVIDENCE_DIR/servlet-agent.log"
echo "Servlet and security locked-Agent fixture: PASS"
