#!/usr/bin/env bash
set -euo pipefail

# Runs one topology through the shared public HTTP and Kafka-recovery phases.
HARNESS_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$HARNESS_DIR/../../.." && pwd)"
TOPOLOGY="${1:-}"
CONFIG_FILE="$HARNESS_DIR/topologies/$TOPOLOGY.conf"
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Unknown topology '$TOPOLOGY'; expected microservices or business-core-monolith" >&2
  exit 64
fi
# shellcheck source=port-plan.sh
source "$HARNESS_DIR/port-plan.sh"
reference_configure_port_plan "$TOPOLOGY"
# shellcheck source=/dev/null
source "$CONFIG_FILE"

REFERENCE_PRODUCT_DIR="$PROJECT_ROOT/verification/reference-product"
COMPOSE_DIR="$REFERENCE_PRODUCT_DIR/compose"
ACCEPTANCE_DIR="$PROJECT_ROOT/verification/acceptance"
COMPOSE_FILE="$COMPOSE_DIR/docker-compose.yml"
RUN_ID="slot$RUN_SLOT-$(date +%s)-$$-$TOPOLOGY"
COMPOSE_PROJECT="${REFERENCE_COMPOSE_PROJECT:-moduvera-reference-$RUN_ID}"
INVENTORY_RESERVE_TOPIC="inventory-reserve-$RUN_ID"
INVENTORY_RESULT_TOPIC="inventory-result-$RUN_ID"
HEALTH_TIMEOUT_SECONDS="${REFERENCE_HEALTH_TIMEOUT_SECONDS:-180}"
APP_STOP_TIMEOUT_SECONDS="${REFERENCE_APP_STOP_TIMEOUT_SECONDS:-5}"
COMPOSE_DOWN_TIMEOUT_SECONDS="${REFERENCE_COMPOSE_DOWN_TIMEOUT_SECONDS:-10}"
REFERENCE_JAVA_TOOL_OPTIONS="${REFERENCE_JAVA_TOOL_OPTIONS:--Xms64m -Xmx256m}"
for timeout_specification in \
  "REFERENCE_APP_STOP_TIMEOUT_SECONDS=$APP_STOP_TIMEOUT_SECONDS" \
  "REFERENCE_COMPOSE_DOWN_TIMEOUT_SECONDS=$COMPOSE_DOWN_TIMEOUT_SECONDS"; do
  timeout_name="${timeout_specification%%=*}"
  timeout_value="${timeout_specification#*=}"
  if [[ ! "$timeout_value" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid $timeout_name '$timeout_value'; expected a positive integer number of seconds" >&2
    exit 64
  fi
done
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-reference.XXXXXX")"
PORT_MANIFEST="${REFERENCE_PORT_MANIFEST:-$RUN_DIR/port-manifest.json}"
REFERENCE_MANIFEST_RUN_ID="$RUN_ID"
REFERENCE_MANIFEST_COMPOSE_PROJECT="$COMPOSE_PROJECT"
REFERENCE_MANIFEST_RUN_DIRECTORY="$RUN_DIR"
REFERENCE_MANIFEST_INVENTORY_RESERVE_TOPIC="$INVENTORY_RESERVE_TOPIC"
REFERENCE_MANIFEST_INVENTORY_RESULT_TOPIC="$INVENTORY_RESULT_TOPIC"
STATE_FILE="$RUN_DIR/recovery-order-id"
FAILED=1
COMPOSE_STARTED=0
JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then JAVA_BIN="$(command -v java || true)"; fi

compose() { docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" "$@"; }

compose_down() {
  python3 "$HARNESS_DIR/bounded_process.py" "$COMPOSE_DOWN_TIMEOUT_SECONDS" -- \
    docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" down \
      --timeout "$COMPOSE_DOWN_TIMEOUT_SECONDS" -v --remove-orphans
}

diagnostics() {
  echo "Reference-product diagnostics for $REFERENCE_TOPOLOGY_NAME (secrets and payloads omitted)" >&2
  compose ps >&2 || true
  for database in orders inventory; do
    if [[ "$(compose exec -T postgres psql -At -U postgres -d "$database" -c "SELECT to_regclass('moduvera_message_outbox')" 2>/dev/null || true)" == "moduvera_message_outbox" ]]; then
      compose exec -T postgres psql -U postgres -d "$database" -c \
        "SELECT message_id,status,attempt_count,next_attempt_at,published_at,terminal_at,last_failure FROM moduvera_message_outbox ORDER BY occurred_at DESC LIMIT 12" >&2 || true
    fi
  done
  for app in identity catalog order inventory monolith gateway; do
    if [[ -f "$RUN_DIR/$app.log" ]]; then
      echo "--- $app (last 80 lines)" >&2
      tail -80 "$RUN_DIR/$app.log" >&2 || true
    fi
  done
  compose logs --tail=80 postgres kafka >&2 || true
  compose exec -T kafka kafka-consumer-groups --bootstrap-server localhost:29092 --list >&2 || true
}

stop_apps() {
  local app pid deadline active
  local pids=()
  for app in "$@"; do
    if [[ -f "$RUN_DIR/$app.pid" ]]; then
      pid="$(<"$RUN_DIR/$app.pid")"
      if [[ "$pid" =~ ^[1-9][0-9]*$ ]] && kill -0 "$pid" 2>/dev/null; then
        pids+=("$pid")
      fi
    fi
  done
  [[ -n "${pids[*]-}" ]] || return 0
  for pid in "${pids[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
  deadline=$((SECONDS + APP_STOP_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    active=0
    for pid in "${pids[@]}"; do
      if kill -0 "$pid" 2>/dev/null; then active=$((active + 1)); fi
    done
    (( active == 0 )) && break
    sleep 0.1
  done
  for pid in "${pids[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
  done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
}

cleanup() {
  local primary_status=$? cleanup_status=0 compose_status=0
  trap - EXIT INT TERM
  set +e
  stop_apps gateway monolith inventory order catalog identity
  if [[ $FAILED -ne 0 && $primary_status -ne 0 && $COMPOSE_STARTED -eq 1 ]]; then diagnostics; fi
  if [[ $COMPOSE_STARTED -eq 1 ]]; then
    compose_down >/dev/null 2>"$RUN_DIR/compose-down-cleanup.err"
    compose_status=$?
    if [[ $compose_status -ne 0 ]]; then
      echo "Reference cleanup failure: docker compose down failed for $COMPOSE_PROJECT" >&2
      if [[ $compose_status -eq 124 ]]; then
        echo "Reference cleanup detail: compose down exceeded the ${COMPOSE_DOWN_TIMEOUT_SECONDS}s wall-clock deadline" >&2
      else
        echo "Reference cleanup detail: compose down exited with status $compose_status" >&2
      fi
      cleanup_status=70
    fi
  fi
  if ! reference_release_run_locks; then
    echo "Reference cleanup failure: one or more owned run locks could not be released" >&2
    cleanup_status=70
  fi
  if ! rm -rf "$RUN_DIR"; then
    echo "Reference cleanup failure: unable to remove temporary directory $RUN_DIR" >&2
    cleanup_status=70
  fi
  if [[ $primary_status -ne 0 ]]; then
    exit "$primary_status"
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

start_app() {
  local app="$1" jar="$2" java_tool_options="$REFERENCE_JAVA_TOOL_OPTIONS" debug_port
  shift 2
  if [[ "$REFERENCE_DEBUG" == "1" ]]; then
    debug_port="$(reference_debug_port "$app")"
    java_tool_options="$java_tool_options -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$debug_port"
  fi
  env "JAVA_TOOL_OPTIONS=$java_tool_options" "$@" \
    "$JAVA_BIN" -jar "$PROJECT_ROOT/$jar" >"$RUN_DIR/$app.log" 2>&1 &
  echo $! >"$RUN_DIR/$app.pid"
}

wait_http() {
  local app="$1" url="$2" deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if curl --fail --silent --max-time 2 "$url" >/dev/null 2>&1; then return 0; fi
    if [[ -f "$RUN_DIR/$app.pid" ]] && ! kill -0 "$(<"$RUN_DIR/$app.pid")" 2>/dev/null; then
      echo "$app stopped before becoming healthy" >&2
      return 1
    fi
    sleep 1
  done
  echo "$app did not become healthy before timeout" >&2
  return 1
}

wait_kafka() {
  local deadline=$((SECONDS + 90))
  while (( SECONDS < deadline )); do
    if compose exec -T kafka kafka-topics --bootstrap-server localhost:29092 --list >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Kafka did not become ready before timeout" >&2
  return 1
}

wait_postgres() {
  local deadline=$((SECONDS + 90))
  while (( SECONDS < deadline )); do
    if compose logs postgres 2>&1 | grep -q "PostgreSQL init process complete" \
      && compose exec -T postgres pg_isready -U postgres -d postgres >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "PostgreSQL did not complete initialization before timeout" >&2
  return 1
}

seed() {
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d identity \
    < "$COMPOSE_DIR/seed-identity.sql" >/dev/null
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d "$CATALOG_SEED_DATABASE" \
    < "$COMPOSE_DIR/seed-catalog.sql" >/dev/null
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d "$INVENTORY_SEED_DATABASE" \
    < "$COMPOSE_DIR/seed-inventory.sql" >/dev/null
}

COMMON_KAFKA=("KAFKA_BROKERS=localhost:$KAFKA_PORT" "INVENTORY_RESERVE_TOPIC=$INVENTORY_RESERVE_TOPIC" "INVENTORY_RESULT_TOPIC=$INVENTORY_RESULT_TOPIC")
DISPOSABLE_DATABASE_MIGRATION=(
  "MODUVERA_DATABASE_MIGRATION_MODE=startup"
  "MODUVERA_DATABASE_MIGRATION_INITIALIZE=true"
)

start_identity() {
  start_app identity apps/identity-app/target/identity-app-0.1.0-SNAPSHOT.jar \
    "IDENTITY_PORT=$IDENTITY_PORT" "IDENTITY_DATABASE_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/identity" \
    IDENTITY_DATABASE_USER=identity IDENTITY_DATABASE_PASSWORD=identity-reference \
    "IDENTITY_ISSUER=http://localhost:$IDENTITY_PORT" "${DISPOSABLE_DATABASE_MIGRATION[@]}"
  wait_http identity "http://localhost:$IDENTITY_PORT/actuator/health"
}

start_catalog() {
  start_app catalog apps/catalog-app/target/catalog-app-0.1.0-SNAPSHOT.jar \
    "CATALOG_PORT=$CATALOG_PORT" "CATALOG_DB_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/catalog" \
    CATALOG_DB_USERNAME=catalog CATALOG_DB_PASSWORD=catalog-reference \
    "IDENTITY_ISSUER_URI=http://localhost:$IDENTITY_PORT" "IDENTITY_JWKS_URI=http://localhost:$IDENTITY_PORT/oauth2/jwks" \
    "${DISPOSABLE_DATABASE_MIGRATION[@]}"
  wait_http catalog "http://localhost:$CATALOG_PORT/actuator/health"
}

start_order() {
  start_app order apps/order-app/target/order-app-0.1.0-SNAPSHOT.jar \
    "SERVER_PORT=$ORDER_PORT" "ORDER_DATABASE_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/orders" \
    ORDER_DATABASE_USERNAME=orders ORDER_DATABASE_PASSWORD=order-reference \
    "IDENTITY_ISSUER_URI=http://localhost:$IDENTITY_PORT" "IDENTITY_JWKS_URI=http://localhost:$IDENTITY_PORT/oauth2/jwks" \
    "IDENTITY_BASE_URL=http://localhost:$IDENTITY_PORT" ORDER_SERVICE_ID=order-service ORDER_SERVICE_SECRET=order-secret \
    "CATALOG_BASE_URL=http://localhost:$CATALOG_PORT" MODUVERA_IDENTIFIER_WORKER_ID=1 \
    "${COMMON_KAFKA[@]}" "${DISPOSABLE_DATABASE_MIGRATION[@]}"
  wait_http order "http://localhost:$ORDER_PORT/actuator/health"
}

start_inventory() {
  start_app inventory apps/inventory-app/target/inventory-app-0.1.0-SNAPSHOT.jar \
    "INVENTORY_PORT=$INVENTORY_PORT" "INVENTORY_DATABASE_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/inventory" \
    INVENTORY_DATABASE_USERNAME=inventory INVENTORY_DATABASE_PASSWORD=inventory-reference \
    "${COMMON_KAFKA[@]}" "${DISPOSABLE_DATABASE_MIGRATION[@]}"
  wait_http inventory "http://localhost:$INVENTORY_PORT/actuator/health"
}

start_monolith() {
  start_app monolith apps/app-monolith/target/app-monolith-0.1.0-SNAPSHOT.jar \
    "SERVER_PORT=$MONOLITH_PORT" "BUSINESS_DATABASE_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/orders" \
    BUSINESS_DATABASE_USERNAME=orders BUSINESS_DATABASE_PASSWORD=order-reference \
    "IDENTITY_ISSUER_URI=http://localhost:$IDENTITY_PORT" "IDENTITY_JWKS_URI=http://localhost:$IDENTITY_PORT/oauth2/jwks" \
    MODUVERA_IDENTIFIER_WORKER_ID=1 "${COMMON_KAFKA[@]}" "${DISPOSABLE_DATABASE_MIGRATION[@]}"
  wait_http monolith "http://localhost:$MONOLITH_PORT/actuator/health"
}

start_business_apps() { local app; for app in "${BUSINESS_APPS[@]}"; do "start_$app"; done; }
stop_business_apps() { stop_apps "${BUSINESS_APPS[@]}"; }

cd "$PROJECT_ROOT"
reference_write_port_manifest "$TOPOLOGY" "$PORT_MANIFEST"
reference_acquire_run_locks
reference_preflight_ports "$HARNESS_DIR"
echo "Reference port preflight: PASS"
if [[ "${REFERENCE_PREFLIGHT_ONLY:-0}" == "1" ]]; then
  FAILED=0
  exit 0
fi

for executable in docker curl uv; do
  command -v "$executable" >/dev/null 2>&1 || { echo "Required executable is unavailable: $executable" >&2; exit 127; }
done
[[ -n "$JAVA_BIN" ]] || { echo "Required executable is unavailable: java" >&2; exit 127; }

compose_down >/dev/null 2>&1
COMPOSE_STARTED=1
compose up -d postgres zookeeper kafka
wait_postgres
wait_kafka
start_identity
start_business_apps

start_app gateway apps/gateway-app/target/gateway-app-0.1.0-SNAPSHOT.jar \
  "GATEWAY_PORT=$GATEWAY_PORT" "IDENTITY_BASE_URL=http://localhost:$IDENTITY_PORT" \
  "ORDER_BASE_URL=$ORDER_TARGET_BASE_URL" "ORDER_TARGET_PRESERVES_PREFIX=$ORDER_TARGET_PRESERVES_PREFIX" \
  GATEWAY_SERVICE_ID=gateway GATEWAY_SERVICE_SECRET=gateway-secret
wait_http gateway "http://localhost:$GATEWAY_PORT/actuator/health"

seed
REFERENCE_TOPOLOGY="$REFERENCE_TOPOLOGY_NAME" REFERENCE_GATEWAY_BASE="http://localhost:$GATEWAY_PORT" \
  UV_CACHE_DIR="${UV_CACHE_DIR:-$RUN_DIR/uv-cache}" \
  uv run --project "$ACCEPTANCE_DIR" pytest -q -s \
  "$ACCEPTANCE_DIR/tests/test_reference_product.py" -k public_order_fulfillment_reference_product

compose stop kafka >/dev/null
REFERENCE_TOPOLOGY="$REFERENCE_TOPOLOGY_NAME" \
  python3 "$HARNESS_DIR/blackbox.py" create-pending "http://localhost:$GATEWAY_PORT" "$STATE_FILE"
stop_business_apps
compose start kafka >/dev/null
wait_kafka
start_business_apps
REFERENCE_TOPOLOGY="$REFERENCE_TOPOLOGY_NAME" \
  python3 "$HARNESS_DIR/blackbox.py" resume "http://localhost:$GATEWAY_PORT" "$STATE_FILE"

FAILED=0
echo "Reference product verification: PASS (topology=$REFERENCE_TOPOLOGY_NAME; public contract + Kafka recovery)"
if [[ "${REFERENCE_KEEP_RUNNING:-0}" == "1" ]]; then
  echo "Reference product remains available at http://localhost:$GATEWAY_PORT; press Ctrl-C to stop."
  while true; do sleep 30; done
fi
