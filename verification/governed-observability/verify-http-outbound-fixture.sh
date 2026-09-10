#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=agent-runtime.sh
source "$SCRIPT_DIR/agent-runtime.sh"
AGENT="${MODUVERA_OTEL_JAVAAGENT:-}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-}"
EVIDENCE_DIR="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:-}"
[[ -d "$EVIDENCE_DIR" ]] || { echo "HTTP outbound fixture evidence directory is required" >&2; exit 64; }
governed_agent_preflight "$AGENT" "$EXTENSION" > "$EVIDENCE_DIR/http-outbound-agent-preflight.log"
# Fixtures use the actual Agent propagator to supply operation Context; Agent owns HTTP client/server Spans.
OPTIONS="$(governed_agent_java_options http-outbound-ticket06 "$AGENT" "$EXTENSION") -Dmoduvera.test.agent=true"
cd "$PROJECT_ROOT"
set +e
./mvnw -pl services/order/order-service,apps/gateway-app -am test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=OrderHttpDiagnosticsIT,IdentityTokenExchangeClientIT \
  -Dfailsafe.failIfNoSpecifiedTests=false "-DargLine=$OPTIONS" \
  > "$EVIDENCE_DIR/http-outbound-agent.log" 2>&1
result=$?
set -e
printf '%s\n' "$result" > "$EVIDENCE_DIR/http-outbound-agent.exit"
[[ "$result" == 0 ]] || { tail -40 "$EVIDENCE_DIR/http-outbound-agent.log"; exit "$result"; }
rg -q 'Governed OpenTelemetry Agent extension handshake: PASS' "$EVIDENCE_DIR/http-outbound-agent.log"
echo "HTTP outbound locked-Agent fixture: PASS"
