#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OBSERVABILITY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$OBSERVABILITY_DIR/../.." && pwd)"
AGENT="${1:-}"
EXTENSION="${2:-}"
FIXTURE_CLASS=io.github.ande1922.moduvera.verification.otel.RuntimeReadyFixture
TEST_CLASSES="$OBSERVABILITY_DIR/agent-extension/target/test-classes"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-otel-handshake.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT

[[ -f "$AGENT" && -f "$EXTENSION" && -f "$TEST_CLASSES/${FIXTURE_CLASS//.//}.class" ]] \
  || { echo "handshake test requires built Agent, extension, and fixture" >&2; exit 64; }

# Keep the guard class and manifest intact but remove the SPI provider declaration.
cp "$EXTENSION" "$TEST_DIR/broken-extension.jar"
mkdir -p "$TEST_DIR/empty/META-INF/services"
: > "$TEST_DIR/empty/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider"
jar --update --file "$TEST_DIR/broken-extension.jar" -C "$TEST_DIR/empty" META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider

run_fixture() {
  local extension="$1" expected_digest="$2" output="$3" options status
  set +e
  (
    source "$OBSERVABILITY_DIR/agent-runtime.sh"
    GOVERNED_AGENT_EXTENSION_SHA256="$expected_digest"
    REFERENCE_JAVA_TOOL_OPTIONS=""
    REFERENCE_OTLP_TRACES_ENDPOINT=http://127.0.0.1:1/v1/traces
    governed_agent_preflight "$AGENT" "$extension"
    options="$(governed_agent_java_options handshake-fixture "$AGENT" "$extension")"
    JAVA_TOOL_OPTIONS="$options" java -cp "$TEST_CLASSES" "$FIXTURE_CLASS"
  ) >"$output" 2>&1
  status=$?
  set -e
  return "$status"
}

run_fixture "$EXTENSION" "$(shasum -a 256 "$EXTENSION" | awk '{print $1}')" "$TEST_DIR/valid.out"
grep -Fq 'Governed OpenTelemetry Agent extension handshake: PASS' "$TEST_DIR/valid.out"
grep -Fxq 'BUSINESS_READY' "$TEST_DIR/valid.out"

if run_fixture "$TEST_DIR/broken-extension.jar" \
  "$(shasum -a 256 "$TEST_DIR/broken-extension.jar" | awk '{print $1}')" "$TEST_DIR/broken.out"; then
  status=0
else
  status=$?
fi
[[ $status -ne 0 ]] || { echo "broken SPI extension unexpectedly reached application main" >&2; exit 1; }
grep -Fq 'Governed OpenTelemetry Agent extension handshake: FAILED' "$TEST_DIR/broken.out"
! grep -Fxq 'BUSINESS_READY' "$TEST_DIR/broken.out" \
  || { echo "broken SPI extension reached BUSINESS_READY" >&2; exit 1; }

echo "Governed Agent extension handshake: PASS (valid SPI reaches main; broken SPI aborts before main)"
