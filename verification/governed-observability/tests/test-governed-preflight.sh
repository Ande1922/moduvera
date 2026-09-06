#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OBSERVABILITY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
AGENT="${1:-}"
EXTENSION="${2:-}"
JACOCO="${3:-}"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-otel-preflight.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT

[[ -f "$AGENT" && -f "$EXTENSION" && -f "$JACOCO" ]] \
  || { echo "usage: test-governed-preflight.sh <verified-agent> <verified-extension> <verified-jacoco>" >&2; exit 64; }
printf 'not the pinned Agent\n' > "$TEST_DIR/bad-agent.jar"
printf 'not the governed extension\n' > "$TEST_DIR/bad-extension.jar"

run_preflight() {
  local agent_path="$1" extension_path="$2" output="$3"
  local trace_endpoint="${TEST_OTLP_TRACES_ENDPOINT:-http://127.0.0.1:14318/v1/traces}"
  shift 3
  env REFERENCE_OTEL_JAVAAGENT="$agent_path" \
    REFERENCE_OTEL_AGENT_EXTENSION="$extension_path" \
    REFERENCE_JACOCO_AGENT="$JACOCO" \
    REFERENCE_OTLP_TRACES_ENDPOINT="$trace_endpoint" \
    "$@" bash -c 'source "$1"; governed_agent_preflight "$2" "$3" "$4"' \
    _ "$OBSERVABILITY_DIR/agent-runtime.sh" "$agent_path" "$extension_path" "$JACOCO" >"$output" 2>&1
}

run_preflight "$AGENT" "$EXTENSION" "$TEST_DIR/valid.out"
grep -Fq 'Governed OpenTelemetry preflight: PASS' "$TEST_DIR/valid.out"

for case_name in missing bad; do
  agent_path="$TEST_DIR/missing-agent.jar"
  [[ "$case_name" == "bad" ]] && agent_path="$TEST_DIR/bad-agent.jar"
  set +e
  run_preflight "$agent_path" "$EXTENSION" "$TEST_DIR/$case_name.out"
  status=$?
  set -e
  [[ $status -eq 78 ]] || {
    echo "$case_name Agent preflight returned $status, expected 78" >&2
    exit 1
  }
done
grep -Fq 'pinned Agent artifact is missing or unreadable' "$TEST_DIR/missing.out"
grep -Fq 'Agent SHA-256 mismatch' "$TEST_DIR/bad.out"

for case_name in missing-extension bad-extension; do
  extension_path="$TEST_DIR/missing-extension.jar"
  [[ "$case_name" == "bad-extension" ]] && extension_path="$TEST_DIR/bad-extension.jar"
  set +e
  run_preflight "$AGENT" "$extension_path" "$TEST_DIR/$case_name.out"
  status=$?
  set -e
  [[ $status -eq 78 ]] || {
    echo "$case_name preflight returned $status, expected 78" >&2
    exit 1
  }
done
grep -Fq 'governed Agent extension is missing or unreadable' "$TEST_DIR/missing-extension.out"
grep -Fq 'Agent extension SHA-256 mismatch' "$TEST_DIR/bad-extension.out"

set +e
TEST_OTLP_TRACES_ENDPOINT=http://user:password@127.0.0.1:14318/v1/traces \
  run_preflight "$AGENT" "$EXTENSION" "$TEST_DIR/endpoint-userinfo.out"
status=$?
set -e
[[ $status -eq 78 ]] || {
  echo "userinfo endpoint preflight returned $status, expected 78" >&2
  exit 1
}
grep -Fq 'Trace endpoint must not contain userinfo credentials' "$TEST_DIR/endpoint-userinfo.out"

cp "$AGENT" "$TEST_DIR/renamed-agent.jar"
for option_name in JAVA_TOOL_OPTIONS REFERENCE_JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS; do
  set +e
  run_preflight "$AGENT" "$EXTENSION" "$TEST_DIR/$option_name.out" \
    "$option_name=-javaagent:$TEST_DIR/renamed-agent.jar"
  status=$?
  set -e
  [[ $status -eq 78 ]] || {
    echo "$option_name renamed Agent preflight returned $status, expected 78" >&2
    exit 1
  }
  grep -Fq 'unapproved pre-existing Java Agent' "$TEST_DIR/$option_name.out"
done

run_preflight "$AGENT" "$EXTENSION" "$TEST_DIR/jacoco.out" \
  "JAVA_TOOL_OPTIONS=-javaagent:$JACOCO=destfile=$TEST_DIR/jacoco.exec"

set +e
run_preflight "$AGENT" "$EXTENSION" "$TEST_DIR/forged-handshake.out" \
  'JAVA_TOOL_OPTIONS=-Dio.github.ande1922.moduvera.otel.extension.active=true'
status=$?
set -e
[[ $status -eq 78 ]] || {
  echo "forged handshake marker preflight returned $status, expected 78" >&2
  exit 1
}
grep -Fq 'must not predefine the governed extension handshake marker' "$TEST_DIR/forged-handshake.out"

set +e
TEST_OTLP_TRACES_ENDPOINT='http://127.0.0.1 -Dotel.traces.exporter=none/v1/traces' \
  run_preflight "$AGENT" "$EXTENSION" "$TEST_DIR/endpoint-injection.out"
status=$?
set -e
[[ $status -eq 78 ]] || {
  echo "endpoint option injection preflight returned $status, expected 78" >&2
  exit 1
}
grep -Fq 'must not contain whitespace or control characters' "$TEST_DIR/endpoint-injection.out"

for invalid_endpoint in \
  'ftp://127.0.0.1/v1/traces' \
  'http:///v1/traces' \
  'http://127.0.0.1/v1/traces?token=secret' \
  'http://127.0.0.1/v1/traces#fragment' \
  'http://127.0.0.1/v1/traces/extra'; do
  set +e
  TEST_OTLP_TRACES_ENDPOINT="$invalid_endpoint" \
    run_preflight "$AGENT" "$EXTENSION" "$TEST_DIR/invalid-endpoint.out"
  status=$?
  set -e
  [[ $status -eq 78 ]] || {
    echo "invalid endpoint '$invalid_endpoint' returned $status, expected 78" >&2
    exit 1
  }
done

echo "Governed Agent launch preflight: PASS (digests, strict endpoint, all JVM option envs, verified JaCoCo)"
