#!/usr/bin/env bash

GOVERNED_OBSERVABILITY_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=agent.lock
source "$GOVERNED_OBSERVABILITY_DIR/agent.lock"

governed_agent_fail() {
  echo "Governed OpenTelemetry launch failed: $*" >&2
  return 78
}

governed_agent_preflight() {
  local agent_path="${1:-}" extension_path="${2:-${REFERENCE_OTEL_AGENT_EXTENSION:-}}"
  local jacoco_path="${3:-${REFERENCE_JACOCO_AGENT:-}}" actual_digest
  local policy_arguments=()
  [[ -n "$agent_path" ]] || {
    governed_agent_fail "REFERENCE_OTEL_JAVAAGENT is required"
    return
  }
  [[ "$agent_path" != *[[:space:]]* ]] || {
    governed_agent_fail "Agent path must not contain whitespace"
    return
  }
  [[ -f "$agent_path" && -r "$agent_path" ]] || {
    governed_agent_fail "pinned Agent artifact is missing or unreadable: $agent_path"
    return
  }
  actual_digest="$(shasum -a 256 "$agent_path" | awk '{print $1}')"
  [[ "$actual_digest" == "$OTEL_JAVAAGENT_SHA256" ]] || {
    governed_agent_fail "Agent SHA-256 mismatch for $agent_path"
    return
  }
  [[ -n "$extension_path" ]] || {
    governed_agent_fail "REFERENCE_OTEL_AGENT_EXTENSION is required"
    return
  }
  [[ "$extension_path" != *[[:space:]]* ]] || {
    governed_agent_fail "Agent extension path must not contain whitespace"
    return
  }
  [[ -f "$extension_path" && -r "$extension_path" ]] || {
    governed_agent_fail "governed Agent extension is missing or unreadable: $extension_path"
    return
  }
  actual_digest="$(shasum -a 256 "$extension_path" | awk '{print $1}')"
  [[ "$actual_digest" == "$GOVERNED_AGENT_EXTENSION_SHA256" ]] || {
    governed_agent_fail "Agent extension SHA-256 mismatch for $extension_path"
    return
  }
  [[ -n "${REFERENCE_OTLP_TRACES_ENDPOINT:-}" ]] || {
    governed_agent_fail "REFERENCE_OTLP_TRACES_ENDPOINT is required"
    return
  }
  policy_arguments=(
    --endpoint "$REFERENCE_OTLP_TRACES_ENDPOINT"
    --jacoco-sha256 "$JACOCO_AGENT_SHA256"
    --option-env "JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:-}"
    --option-env "REFERENCE_JAVA_TOOL_OPTIONS=${REFERENCE_JAVA_TOOL_OPTIONS:-}"
    --option-env "_JAVA_OPTIONS=${_JAVA_OPTIONS:-}"
    --option-env "JDK_JAVA_OPTIONS=${JDK_JAVA_OPTIONS:-}"
  )
  if [[ -n "$jacoco_path" ]]; then policy_arguments+=(--jacoco-agent "$jacoco_path"); fi
  if ! python3 "$GOVERNED_OBSERVABILITY_DIR/runtime_policy.py" "${policy_arguments[@]}"; then
    governed_agent_fail "runtime policy rejected the launch"
    return
  fi
  printf 'Governed OpenTelemetry preflight: PASS (agent=%s; api=%s; agentSha256=%s; extensionSha256=%s)\n' \
    "$OTEL_JAVAAGENT_VERSION" "$OTEL_API_VERSION" "$OTEL_JAVAAGENT_SHA256" \
    "$GOVERNED_AGENT_EXTENSION_SHA256"
}

governed_agent_java_options() {
  local service_name="$1" runtime_agent_path="${2:-${REFERENCE_OTEL_JAVAAGENT:-}}"
  local runtime_extension_path="${3:-${REFERENCE_OTEL_AGENT_EXTENSION:-}}"
  printf '%s' \
    "-javaagent:$runtime_agent_path" \
    " -Dotel.javaagent.extensions=$runtime_extension_path" \
    " -javaagent:$runtime_extension_path" \
    " -Dotel.service.name=$service_name" \
    " -Dotel.propagators=tracecontext" \
    " -Dotel.traces.exporter=otlp" \
    " -Dotel.exporter.otlp.traces.protocol=http/protobuf" \
    " -Dotel.exporter.otlp.traces.endpoint=$REFERENCE_OTLP_TRACES_ENDPOINT" \
    " -Dotel.logs.exporter=none" \
    " -Dotel.metrics.exporter=none" \
    " -Dotel.resource.disabled.keys=process.command_args,process.command_line" \
    " -Dotel.traces.sampler=parentbased_always_on" \
    " -Dotel.bsp.max.queue.size=512" \
    " -Dotel.bsp.max.export.batch.size=128" \
    " -Dotel.bsp.schedule.delay=100" \
    " -Dotel.bsp.export.timeout=1000" \
    " -Dotel.instrumentation.http.client.capture-request-headers=" \
    " -Dotel.instrumentation.http.client.capture-response-headers=" \
    " -Dotel.instrumentation.http.server.capture-request-headers=" \
    " -Dotel.instrumentation.http.server.capture-response-headers=" \
    " -Dotel.instrumentation.servlet.experimental.capture-request-parameters=" \
    " -Dotel.instrumentation.messaging.experimental.capture-headers=false" \
    " -Dotel.instrumentation.graphql.capture-query=false" \
    " -Dotel.instrumentation.elasticsearch.capture-search-query=false" \
    " -Dotel.instrumentation.jdbc.enabled=false"
}
