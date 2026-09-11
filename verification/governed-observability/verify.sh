#!/usr/bin/env bash
set -euo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
if [[ "$SCRIPT_SOURCE" != */* ]]; then
  SCRIPT_SOURCE="./$SCRIPT_SOURCE"
fi
# shellcheck source=login-fixture-env.sh
source "${SCRIPT_SOURCE%/*}/login-fixture-env.sh"
capture_login_fixture_environment
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$SCRIPT_SOURCE")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
EVIDENCE_DIR="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:-}"
MAVEN_REPO="${MODUVERA_OBSERVABILITY_MAVEN_REPO:-}"
RUN_SLOT="${MODUVERA_OBSERVABILITY_RUN_SLOT:-40}"
AGENT="$EVIDENCE_DIR/opentelemetry-javaagent-2.31.1.jar"
EXTENSION="$PROJECT_ROOT/verification/governed-observability/agent-extension/target/moduvera-governed-otel-agent-extension-0.1.0-SNAPSHOT.jar"
COMPOSE_FILE="$PROJECT_ROOT/verification/reference-product/compose/docker-compose.yml"
COMPOSE_PROJECT=""
RECEIVER_PID=""
IDENTITY_PROXY_PID=""
ORDER_PROXY_PID=""
CATALOG_PROXY_PID=""
HARNESS_PID=""

collect_topology_stdout() {
  [[ -s "$EVIDENCE_DIR/reference-manifest.json" ]] || return 0
  local run_directory
  run_directory="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["resources"]["tempDirectory"])' \
    "$EVIDENCE_DIR/reference-manifest.json")"
  mkdir -p "$EVIDENCE_DIR/stdout"
  for service in gateway identity catalog order inventory; do
    [[ ! -f "$run_directory/$service.log" ]] || cp "$run_directory/$service.log" "$EVIDENCE_DIR/stdout/$service.log"
  done
}

[[ -n "$EVIDENCE_DIR" && -n "$MAVEN_REPO" ]] || {
  echo "MODUVERA_OBSERVABILITY_EVIDENCE_DIR and MODUVERA_OBSERVABILITY_MAVEN_REPO are required" >&2
  exit 64
}
require_login_fixture_environment
mkdir -p "$EVIDENCE_DIR" "$MAVEN_REPO"

stop_process() {
  local pid="$1"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  local primary_status=$?
  trap - EXIT INT TERM
  set +e
  collect_topology_stdout
  stop_process "$HARNESS_PID"
  stop_process "$ORDER_PROXY_PID"
  stop_process "$CATALOG_PROXY_PID"
  stop_process "$IDENTITY_PROXY_PID"
  stop_process "$RECEIVER_PID"
  if [[ -z "$COMPOSE_PROJECT" && -s "$EVIDENCE_DIR/reference-manifest.json" ]]; then
    COMPOSE_PROJECT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["resources"]["composeProject"])' \
      "$EVIDENCE_DIR/reference-manifest.json" 2>/dev/null || true)"
  fi
  if [[ -n "$COMPOSE_PROJECT" ]]; then
    docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" down \
      --timeout 10 -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "$primary_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$SCRIPT_DIR/tests/test-agent-contract.sh"
"$SCRIPT_DIR/fetch-agent.sh" "$AGENT"

if [[ "${MODUVERA_OBSERVABILITY_SKIP_BUILD:-0}" != "1" ]]; then
  "$PROJECT_ROOT/mvnw" -Dmaven.repo.local="$MAVEN_REPO" -q clean install
fi

[[ -f "$EXTENSION" ]] || {
  echo "Governed Agent extension is unavailable after the build: $EXTENSION" >&2
  exit 1
}
JACOCO_AGENT="$(find "$MAVEN_REPO/org/jacoco/org.jacoco.agent/0.8.15" \
  -type f -name 'org.jacoco.agent-0.8.15-runtime.jar' -print -quit 2>/dev/null || true)"
[[ -f "$JACOCO_AGENT" ]] || { echo "JaCoCo 0.8.15 runtime Agent is unavailable after the build" >&2; exit 1; }
"$SCRIPT_DIR/tests/test-governed-preflight.sh" "$AGENT" "$EXTENSION" "$JACOCO_AGENT"
"$SCRIPT_DIR/tests/test-extension-handshake.sh" "$AGENT" "$EXTENSION"

if [[ "${MODUVERA_OBSERVABILITY_SKIP_IMAGE:-0}" != "1" ]]; then
  "$PROJECT_ROOT/verification/application-image/build-images.sh"
  python3 "$PROJECT_ROOT/verification/application-image/inspect_images.py"
fi

python3 "$SCRIPT_DIR/qualification_inputs.py" capture "$PROJECT_ROOT" "$EVIDENCE_DIR/inputs.json"

python3 -c 'import json,secrets,sys; labels=("command","header","query","sql","payload","credential"); json.dump({label:"moduvera-"+label+"-"+secrets.token_hex(16) for label in labels},open(sys.argv[1],"w"),sort_keys=True)' \
  "$EVIDENCE_DIR/sentinels.json"
chmod 0600 "$EVIDENCE_DIR/sentinels.json"

rm -f "$EVIDENCE_DIR/otlp.port" \
  "$EVIDENCE_DIR/identity-proxy.port" "$EVIDENCE_DIR/order-proxy.port" \
  "$EVIDENCE_DIR/catalog-proxy.port" "$EVIDENCE_DIR/probe-ready" "$EVIDENCE_DIR/probe-release"
python3 "$SCRIPT_DIR/otlp_receiver.py" --host 0.0.0.0 --port 0 \
  --port-file "$EVIDENCE_DIR/otlp.port" --output "$EVIDENCE_DIR/spans.jsonl" \
  --status "$EVIDENCE_DIR/receiver-status.json" --sentinels "$EVIDENCE_DIR/sentinels.json" \
  >"$EVIDENCE_DIR/receiver.log" 2>&1 &
RECEIVER_PID=$!
deadline=$((SECONDS + 15))
until [[ -s "$EVIDENCE_DIR/otlp.port" ]]; do
  kill -0 "$RECEIVER_PID" 2>/dev/null || { echo "OTLP receiver stopped during startup" >&2; exit 1; }
  (( SECONDS < deadline )) || { echo "OTLP receiver did not publish its port" >&2; exit 1; }
  sleep 0.1
done
OTLP_PORT="$(<"$EVIDENCE_DIR/otlp.port")"

REFERENCE_OTLP_TRACES_ENDPOINT="http://127.0.0.1:$OTLP_PORT/v1/traces" \
  MODUVERA_OTEL_JAVAAGENT="$AGENT" MODUVERA_OTEL_AGENT_EXTENSION="$EXTENSION" \
  MODUVERA_OBSERVABILITY_SENTINELS="$EVIDENCE_DIR/sentinels.json" \
  MODUVERA_OBSERVABILITY_EVIDENCE_DIR="$EVIDENCE_DIR" \
  "$SCRIPT_DIR/verify-exception-fixture.sh" | tee "$EVIDENCE_DIR/exception-fixture.out"

if [[ "${MODUVERA_OBSERVABILITY_SKIP_IMAGE:-0}" != "1" ]]; then
  REFERENCE_OTLP_TRACES_ENDPOINT="http://host.docker.internal:$OTLP_PORT/v1/traces" \
    MODUVERA_OTEL_JAVAAGENT="$AGENT" MODUVERA_OTEL_AGENT_EXTENSION="$EXTENSION" \
    MODUVERA_JACOCO_AGENT="$JACOCO_AGENT" \
    MODUVERA_OBSERVABILITY_EVIDENCE_DIR="$EVIDENCE_DIR" \
    "$SCRIPT_DIR/verify-image-agent.sh" | tee "$EVIDENCE_DIR/image-agent.out"
fi

POSTGRES_PORT=$((55432 + RUN_SLOT * 100))
KAFKA_PORT=$((59092 + RUN_SLOT * 100))
GATEWAY_PORT=$((58080 + RUN_SLOT * 100))
IDENTITY_PORT=$((58081 + RUN_SLOT * 100))
ORDER_PORT=$((58083 + RUN_SLOT * 100))
CATALOG_PORT=$((58082 + RUN_SLOT * 100))

python3 "$SCRIPT_DIR/header_proxy.py" --name gateway-identity \
  --target "http://127.0.0.1:$IDENTITY_PORT" \
  --port-file "$EVIDENCE_DIR/identity-proxy.port" \
  --output "$EVIDENCE_DIR/identity-headers.jsonl" \
  >"$EVIDENCE_DIR/identity-proxy.log" 2>&1 &
IDENTITY_PROXY_PID=$!
python3 "$SCRIPT_DIR/header_proxy.py" --name gateway-order \
  --target "http://127.0.0.1:$ORDER_PORT" \
  --port-file "$EVIDENCE_DIR/order-proxy.port" \
  --output "$EVIDENCE_DIR/order-headers.jsonl" \
  >"$EVIDENCE_DIR/order-proxy.log" 2>&1 &
ORDER_PROXY_PID=$!
python3 "$SCRIPT_DIR/header_proxy.py" --name order-catalog \
  --target "http://127.0.0.1:$CATALOG_PORT" \
  --port-file "$EVIDENCE_DIR/catalog-proxy.port" \
  --output "$EVIDENCE_DIR/catalog-headers.jsonl" \
  >"$EVIDENCE_DIR/catalog-proxy.log" 2>&1 &
CATALOG_PROXY_PID=$!
deadline=$((SECONDS + 15))
until [[ -s "$EVIDENCE_DIR/identity-proxy.port" && -s "$EVIDENCE_DIR/order-proxy.port" \
  && -s "$EVIDENCE_DIR/catalog-proxy.port" ]]; do
  kill -0 "$IDENTITY_PROXY_PID" 2>/dev/null || { echo "Identity header proxy stopped during startup" >&2; exit 1; }
  kill -0 "$ORDER_PROXY_PID" 2>/dev/null || { echo "Order header proxy stopped during startup" >&2; exit 1; }
  kill -0 "$CATALOG_PROXY_PID" 2>/dev/null || { echo "Catalog header proxy stopped during startup" >&2; exit 1; }
  (( SECONDS < deadline )) || { echo "Header proxies did not publish their ports" >&2; exit 1; }
  sleep 0.1
done
IDENTITY_PROXY_PORT="$(<"$EVIDENCE_DIR/identity-proxy.port")"
ORDER_PROXY_PORT="$(<"$EVIDENCE_DIR/order-proxy.port")"
CATALOG_PROXY_PORT="$(<"$EVIDENCE_DIR/catalog-proxy.port")"

RUN_SLOT="$RUN_SLOT" REFERENCE_SKIP_BUILD=1 REFERENCE_KEEP_RUNNING=1 \
  REFERENCE_STDOUT_EVIDENCE_DIR="$EVIDENCE_DIR/harness-stdout" \
  REFERENCE_JAVA_TOOL_OPTIONS="${REFERENCE_JAVA_TOOL_OPTIONS:--Xms64m -Xmx256m} -Dotel.exporter.otlp.metrics.protocol=http/protobuf -Dotel.exporter.otlp.metrics.endpoint=http://127.0.0.1:$OTLP_PORT/v1/metrics -Dotel.exporter.otlp.logs.protocol=http/protobuf -Dotel.exporter.otlp.logs.endpoint=http://127.0.0.1:$OTLP_PORT/v1/logs -Dotel.metric.export.interval=500" \
  REFERENCE_GOVERNED_OBSERVABILITY=1 REFERENCE_OTEL_JAVAAGENT="$AGENT" \
  REFERENCE_OTEL_AGENT_EXTENSION="$EXTENSION" \
  REFERENCE_OTLP_TRACES_ENDPOINT="http://127.0.0.1:$OTLP_PORT/v1/traces" \
  REFERENCE_GATEWAY_IDENTITY_BASE_URL="http://127.0.0.1:$IDENTITY_PROXY_PORT" \
  REFERENCE_GATEWAY_ORDER_BASE_URL="http://127.0.0.1:$ORDER_PROXY_PORT" \
  REFERENCE_ORDER_IDENTITY_BASE_URL="http://127.0.0.1:$IDENTITY_PROXY_PORT" \
  REFERENCE_ORDER_CATALOG_BASE_URL="http://127.0.0.1:$CATALOG_PROXY_PORT" \
  REFERENCE_GOVERNED_PROBE_READY="$EVIDENCE_DIR/probe-ready" \
  REFERENCE_GOVERNED_PROBE_RELEASE="$EVIDENCE_DIR/probe-release" \
  REFERENCE_PORT_MANIFEST="$EVIDENCE_DIR/reference-manifest.json" \
  "$PROJECT_ROOT/verification/reference-product/harness/verify.sh" microservices \
  >"$EVIDENCE_DIR/reference-harness.log" 2>&1 &
HARNESS_PID=$!
deadline=$((SECONDS + 600))
until [[ -f "$EVIDENCE_DIR/probe-ready" ]]; do
  if ! kill -0 "$HARNESS_PID" 2>/dev/null; then
    wait "$HARNESS_PID" || true
    tail -n 200 "$EVIDENCE_DIR/reference-harness.log" >&2
    echo "Governed reference harness stopped before the cold-cache probe" >&2
    exit 1
  fi
  (( SECONDS < deadline )) || { tail -n 200 "$EVIDENCE_DIR/reference-harness.log" >&2; exit 1; }
  sleep 1
done
GATEWAY_PORT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ports"]["apps"]["gateway"]["application"])' \
  "$EVIDENCE_DIR/reference-manifest.json")"
COMPOSE_PROJECT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["resources"]["composeProject"])' \
  "$EVIDENCE_DIR/reference-manifest.json")"
docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" exec -T postgres \
  psql -At -U postgres -d identity -c \
  "SELECT count(*) FROM identity_service_permission WHERE service_id = 'order-service' AND audience = 'catalog-service' AND permission = 'catalog:read';" \
  > "$EVIDENCE_DIR/identity-service-permission.txt"
grep -Fxq '1' "$EVIDENCE_DIR/identity-service-permission.txt" \
  || { echo "Order service permission seed is unavailable before the cold-cache probe" >&2; exit 1; }

run_login_probe python3 "$SCRIPT_DIR/probe.py" traffic --base "http://127.0.0.1:$GATEWAY_PORT" \
  --identity-headers "$EVIDENCE_DIR/identity-headers.jsonl" \
  --order-headers "$EVIDENCE_DIR/order-headers.jsonl" \
  --catalog-headers "$EVIDENCE_DIR/catalog-headers.jsonl" \
  --sentinels "$EVIDENCE_DIR/sentinels.json" --output "$EVIDENCE_DIR/traffic.json"
SAMPLED_ORDER_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sampledOrder"]["id"])' "$EVIDENCE_DIR/traffic.json")"
for database in orders inventory; do
  message_id="reserve-order-$SAMPLED_ORDER_ID"
  [[ "$database" != inventory ]] || message_id="inventory-result:$message_id"
  docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" exec -T postgres \
    psql -At -U postgres -d "$database" -c \
    "SELECT row_to_json(evidence) FROM (SELECT message_id,correlation_id,tenant_id,actor_type,actor_subject,initiator_type,initiator_subject,partition_key,creation_traceparent,creation_tracestate,publication_traceparent,publication_tracestate,publication_generation,status,attempt_count FROM moduvera_message_outbox WHERE message_id = '$message_id') evidence;" \
    > "$EVIDENCE_DIR/sampled-$database.json"
done
python3 - "$EVIDENCE_DIR" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
value = {key: json.loads((root / f"sampled-{database}.json").read_text())
         for key, database in (("order", "orders"), ("inventory", "inventory"))}
(root / "sampled-database.json").write_text(json.dumps(value, indent=2) + "\n")
PY
: > "$EVIDENCE_DIR/probe-release"

deadline=$((SECONDS + 600))
until grep -Fq 'Reference product remains available' "$EVIDENCE_DIR/reference-harness.log" 2>/dev/null; do
  if ! kill -0 "$HARNESS_PID" 2>/dev/null; then
    wait "$HARNESS_PID" || true
    tail -n 200 "$EVIDENCE_DIR/reference-harness.log" >&2
    echo "Governed reference harness stopped before completing acceptance traffic" >&2
    exit 1
  fi
  (( SECONDS < deadline )) || { tail -n 200 "$EVIDENCE_DIR/reference-harness.log" >&2; exit 1; }
  sleep 1
done

RESERVE_TOPIC="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["resources"]["topics"]["inventoryReserve"])' "$EVIDENCE_DIR/reference-manifest.json")"
RESULT_TOPIC="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["resources"]["topics"]["inventoryResult"])' "$EVIDENCE_DIR/reference-manifest.json")"
kill -TERM "$RECEIVER_PID"
wait "$RECEIVER_PID"
RECEIVER_PID=""
sleep 2
run_login_probe python3 "$SCRIPT_DIR/probe.py" outage --base "http://127.0.0.1:$GATEWAY_PORT" \
  --output "$EVIDENCE_DIR/outage.json"
OUTAGE_ORDER_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["orderId"])' "$EVIDENCE_DIR/outage.json")"
docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" exec -T postgres \
  psql -At -U postgres -d orders -c \
  "SELECT (SELECT count(*) FROM order_header WHERE order_id = $OUTAGE_ORDER_ID) || '|' || (SELECT status FROM order_header WHERE order_id = $OUTAGE_ORDER_ID) || '|' || (SELECT count(*) FROM moduvera_message_outbox WHERE message_id = 'reserve-order-$OUTAGE_ORDER_ID') || '|' || (SELECT status FROM moduvera_message_outbox WHERE message_id = 'reserve-order-$OUTAGE_ORDER_ID') || '|' || (SELECT attempt_count FROM moduvera_message_outbox WHERE message_id = 'reserve-order-$OUTAGE_ORDER_ID') || '|' || (SELECT count(*) FROM moduvera_message_inbox WHERE message_id = 'inventory-result:reserve-order-$OUTAGE_ORDER_ID');" \
  > "$EVIDENCE_DIR/outage-order-database.txt"
docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" exec -T postgres \
  psql -At -U postgres -d inventory -c \
  "SELECT (SELECT count(*) FROM inventory_reservation_result WHERE order_id = $OUTAGE_ORDER_ID) || '|' || (SELECT result_type FROM inventory_reservation_result WHERE order_id = $OUTAGE_ORDER_ID) || '|' || (SELECT count(*) FROM moduvera_message_inbox WHERE message_id = 'reserve-order-$OUTAGE_ORDER_ID') || '|' || (SELECT count(*) FROM moduvera_message_outbox WHERE message_id = 'inventory-result:reserve-order-$OUTAGE_ORDER_ID') || '|' || (SELECT status FROM moduvera_message_outbox WHERE message_id = 'inventory-result:reserve-order-$OUTAGE_ORDER_ID') || '|' || (SELECT attempt_count FROM moduvera_message_outbox WHERE message_id = 'inventory-result:reserve-order-$OUTAGE_ORDER_ID');" \
  > "$EVIDENCE_DIR/outage-inventory-database.txt"

: > "$EVIDENCE_DIR/kafka-records.txt"
for topic in "$RESERVE_TOPIC" "$RESULT_TOPIC"; do
  docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" exec -T kafka \
    kafka-console-consumer --bootstrap-server localhost:29092 --topic "$topic" \
    --from-beginning --timeout-ms 3000 --property print.headers=true \
    --property print.key=true --property print.value=true \
    >> "$EVIDENCE_DIR/kafka-records.txt" 2>> "$EVIDENCE_DIR/kafka-records.err" || true
done

collect_topology_stdout
python3 "$SCRIPT_DIR/qualification_inputs.py" check "$PROJECT_ROOT" "$EVIDENCE_DIR/inputs.json"
python3 "$SCRIPT_DIR/probe.py" analyze \
  --receiver-status "$EVIDENCE_DIR/receiver-status.json" --spans "$EVIDENCE_DIR/spans.jsonl" \
  --traffic "$EVIDENCE_DIR/traffic.json" --outage "$EVIDENCE_DIR/outage.json" \
  --kafka-records "$EVIDENCE_DIR/kafka-records.txt" \
  --outage-order-database "$EVIDENCE_DIR/outage-order-database.txt" \
  --outage-inventory-database "$EVIDENCE_DIR/outage-inventory-database.txt" \
  --sampled-database "$EVIDENCE_DIR/sampled-database.json" \
  --stdout "$EVIDENCE_DIR/stdout" --sentinels "$EVIDENCE_DIR/sentinels.json" \
  --output "$EVIDENCE_DIR/qualification.json"

stop_process "$HARNESS_PID"
HARNESS_PID=""
echo "Governed OpenTelemetry Agent verification: PASS"
