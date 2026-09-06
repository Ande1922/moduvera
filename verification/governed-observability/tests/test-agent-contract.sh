#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OBSERVABILITY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$OBSERVABILITY_DIR/../.." && pwd)"
# shellcheck source=../agent.lock
source "$OBSERVABILITY_DIR/agent.lock"

fail() { echo "governed Agent contract test failed: $*" >&2; exit 1; }
require_option() {
  grep -Fq -- "$1" "$OBSERVABILITY_DIR/agent-runtime.sh" \
    || fail "runtime options are missing $1"
}

[[ "$OTEL_JAVAAGENT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || fail "Agent version is not fixed"
[[ "$OTEL_JAVAAGENT_URL" == *"/v$OTEL_JAVAAGENT_VERSION/opentelemetry-javaagent.jar" ]] \
  || fail "Agent URL is not tied to the fixed version"
[[ "$OTEL_JAVAAGENT_URL" != *latest* ]] || fail "Agent URL uses latest"
[[ "$OTEL_JAVAAGENT_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "Agent digest is not SHA-256"
[[ "$OTEL_API_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "API version is not fixed"
[[ "$GOVERNED_AGENT_EXTENSION_SHA256" =~ ^[0-9a-f]{64}$ ]] \
  || fail "Agent extension digest is not SHA-256"

for option in \
  '-Dotel.propagators=tracecontext' \
  '-Dotel.javaagent.extensions=' \
  '-javaagent:$runtime_extension_path' \
  '-Dotel.traces.exporter=otlp' \
  '-Dotel.exporter.otlp.traces.protocol=http/protobuf' \
  '-Dotel.logs.exporter=none' \
  '-Dotel.metrics.exporter=none' \
  '-Dotel.resource.disabled.keys=process.command_args,process.command_line' \
  '-Dotel.traces.sampler=parentbased_always_on' \
  '-Dotel.bsp.max.queue.size=512' \
  '-Dotel.bsp.max.export.batch.size=128' \
  '-Dotel.bsp.export.timeout=1000' \
  '-Dotel.instrumentation.http.client.capture-request-headers=' \
  '-Dotel.instrumentation.http.client.capture-response-headers=' \
  '-Dotel.instrumentation.http.server.capture-request-headers=' \
  '-Dotel.instrumentation.http.server.capture-response-headers=' \
  '-Dotel.instrumentation.servlet.experimental.capture-request-parameters=' \
  '-Dotel.instrumentation.messaging.experimental.capture-headers=false' \
  '-Dotel.instrumentation.graphql.capture-query=false' \
  '-Dotel.instrumentation.elasticsearch.capture-search-query=false' \
  '-Dotel.instrumentation.jdbc.enabled=false'; do
  require_option "$option"
done

SERVICE_FILE="$OBSERVABILITY_DIR/agent-extension/src/main/resources/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider"
grep -Fxq 'io.github.ande1922.moduvera.verification.otel.GovernedAgentExtension' "$SERVICE_FILE" \
  || fail "Agent extension service provider registration is missing"

grep -Fq '<artifactId>opentelemetry-api</artifactId>' "$PROJECT_ROOT/framework/bom/pom.xml" \
  || fail "published BOM does not expose the governed OTel API"
grep -Fq '<artifactId>opentelemetry-context</artifactId>' "$PROJECT_ROOT/framework/bom/pom.xml" \
  || fail "published BOM does not align the governed OTel Context API"
grep -Fq '<artifactId>opentelemetry-api</artifactId>' \
  "$PROJECT_ROOT/framework/testing/moduvera-bom-smoke/pom.xml" \
  || fail "BOM consumer smoke does not exercise the governed OTel API"
grep -R -i -E 'opentelemetry-sdk|micrometer-tracing|brave|zipkin' \
  "$PROJECT_ROOT"/apps/*/pom.xml "$PROJECT_ROOT"/framework/*/*/pom.xml \
  && fail "production modules attach a second tracing SDK or bridge"

for script in "$OBSERVABILITY_DIR"/*.sh "$SCRIPT_DIR"/*.sh; do bash -n "$script"; done
python3 -c 'import ast, pathlib, sys; [ast.parse(pathlib.Path(p).read_text()) for p in sys.argv[1:]]' \
  "$OBSERVABILITY_DIR/header_proxy.py" "$OBSERVABILITY_DIR/otlp_receiver.py" \
  "$OBSERVABILITY_DIR/probe.py" "$OBSERVABILITY_DIR/runtime_policy.py"
"$SCRIPT_DIR/test-login-fixture-environment.sh"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s "$SCRIPT_DIR" -p 'test_*.py'

echo "Governed Agent static contract: PASS"
