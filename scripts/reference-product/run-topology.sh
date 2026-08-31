#!/usr/bin/env bash
set -euo pipefail

# Runs one topology through the shared public HTTP and Kafka-recovery phases.
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOPOLOGY="${1:-}"
CONFIG_FILE="$PROJECT_ROOT/scripts/reference-product/topologies/$TOPOLOGY.conf"
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Unknown topology '$TOPOLOGY'; expected microservices or business-core-monolith" >&2
  exit 64
fi
# shellcheck source=/dev/null
source "$CONFIG_FILE"

COMPOSE_FILE="$PROJECT_ROOT/deployment/reference/docker-compose.yml"
RUN_ID="$(date +%s)-$$-$TOPOLOGY"
COMPOSE_PROJECT="${REFERENCE_COMPOSE_PROJECT:-platform-reference-$RUN_ID}"
KAFKA_PORT="${REFERENCE_KAFKA_PORT:-59092}"
POSTGRES_PORT="${REFERENCE_POSTGRES_PORT:-55432}"
GATEWAY_PORT="${REFERENCE_GATEWAY_PORT:-58080}"
IDENTITY_PORT="${REFERENCE_IDENTITY_PORT:-58081}"
CATALOG_PORT="${REFERENCE_CATALOG_PORT:-58082}"
ORDER_PORT="${REFERENCE_ORDER_PORT:-58083}"
INVENTORY_PORT="${REFERENCE_INVENTORY_PORT:-58084}"
MONOLITH_PORT="${REFERENCE_MONOLITH_PORT:-58085}"
HEALTH_TIMEOUT_SECONDS="${REFERENCE_HEALTH_TIMEOUT_SECONDS:-180}"
REFERENCE_JAVA_TOOL_OPTIONS="${REFERENCE_JAVA_TOOL_OPTIONS:--Xms64m -Xmx256m}"
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/platform-reference.XXXXXX")"
STATE_FILE="$RUN_DIR/recovery-order-id"
FAILED=1
JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then JAVA_BIN="$(command -v java || true)"; fi

compose() { docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" "$@"; }

diagnostics() {
  echo "Reference-product diagnostics for $REFERENCE_TOPOLOGY_NAME (secrets and payloads omitted)" >&2
  compose ps >&2 || true
  for database in orders inventory; do
    if [[ "$(compose exec -T postgres psql -At -U postgres -d "$database" -c "SELECT to_regclass('platform_message_outbox')" 2>/dev/null || true)" == "platform_message_outbox" ]]; then
      compose exec -T postgres psql -U postgres -d "$database" -c \
        "SELECT message_id,status,attempt_count,next_attempt_at,published_at,terminal_at,last_failure FROM platform_message_outbox ORDER BY occurred_at DESC LIMIT 12" >&2 || true
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

stop_app() {
  local app="$1"
  if [[ -f "$RUN_DIR/$app.pid" ]]; then
    local pid
    pid="$(<"$RUN_DIR/$app.pid")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      for _ in {1..20}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
      kill -9 "$pid" 2>/dev/null || true
    fi
  fi
}

cleanup() {
  local exit_code=$?
  for app in gateway monolith inventory order catalog identity; do stop_app "$app"; done
  if [[ $FAILED -ne 0 && $exit_code -ne 0 ]]; then diagnostics; fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$RUN_DIR"
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

start_app() {
  local app="$1" jar="$2"
  shift 2
  env "JAVA_TOOL_OPTIONS=$REFERENCE_JAVA_TOOL_OPTIONS" "$@" \
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
    < "$PROJECT_ROOT/deployment/reference/seed-identity.sql" >/dev/null
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d "$CATALOG_SEED_DATABASE" \
    < "$PROJECT_ROOT/deployment/reference/seed-catalog.sql" >/dev/null
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d "$INVENTORY_SEED_DATABASE" \
    < "$PROJECT_ROOT/deployment/reference/seed-inventory.sql" >/dev/null
}

COMMON_KAFKA=("KAFKA_BROKERS=localhost:$KAFKA_PORT" "INVENTORY_RESERVE_TOPIC=inventory-reserve-$RUN_ID" "INVENTORY_RESULT_TOPIC=inventory-result-$RUN_ID")

start_identity() {
  start_app identity apps/identity-app/target/identity-app-0.1.0-SNAPSHOT.jar \
    "IDENTITY_PORT=$IDENTITY_PORT" "IDENTITY_DATABASE_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/identity" \
    IDENTITY_DATABASE_USER=identity IDENTITY_DATABASE_PASSWORD=identity-reference \
    "IDENTITY_ISSUER=http://localhost:$IDENTITY_PORT"
  wait_http identity "http://localhost:$IDENTITY_PORT/actuator/health"
}

start_catalog() {
  start_app catalog apps/catalog-app/target/catalog-app-0.1.0-SNAPSHOT.jar \
    "CATALOG_PORT=$CATALOG_PORT" "CATALOG_DB_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/catalog" \
    CATALOG_DB_USERNAME=catalog CATALOG_DB_PASSWORD=catalog-reference \
    "PLATFORM_JWT_ISSUER=http://localhost:$IDENTITY_PORT" "PLATFORM_JWKS_URI=http://localhost:$IDENTITY_PORT/oauth2/jwks"
  wait_http catalog "http://localhost:$CATALOG_PORT/actuator/health"
}

start_order() {
  start_app order apps/order-app/target/order-app-0.1.0-SNAPSHOT.jar \
    "SERVER_PORT=$ORDER_PORT" "ORDER_DATABASE_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/orders" \
    ORDER_DATABASE_USERNAME=orders ORDER_DATABASE_PASSWORD=order-reference \
    "IDENTITY_ISSUER_URI=http://localhost:$IDENTITY_PORT" "IDENTITY_JWKS_URI=http://localhost:$IDENTITY_PORT/oauth2/jwks" \
    "IDENTITY_BASE_URL=http://localhost:$IDENTITY_PORT" ORDER_SERVICE_ID=order-service ORDER_SERVICE_SECRET=order-secret \
    "CATALOG_BASE_URL=http://localhost:$CATALOG_PORT" "${COMMON_KAFKA[@]}"
  wait_http order "http://localhost:$ORDER_PORT/actuator/health"
}

start_inventory() {
  start_app inventory apps/inventory-app/target/inventory-app-0.1.0-SNAPSHOT.jar \
    "INVENTORY_PORT=$INVENTORY_PORT" "INVENTORY_DATABASE_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/inventory" \
    INVENTORY_DATABASE_USERNAME=inventory INVENTORY_DATABASE_PASSWORD=inventory-reference "${COMMON_KAFKA[@]}"
  wait_http inventory "http://localhost:$INVENTORY_PORT/actuator/health"
}

start_monolith() {
  start_app monolith apps/app-monolith/target/app-monolith-0.1.0-SNAPSHOT.jar \
    "SERVER_PORT=$MONOLITH_PORT" "BUSINESS_DATABASE_URL=jdbc:postgresql://localhost:$POSTGRES_PORT/orders" \
    BUSINESS_DATABASE_USERNAME=orders BUSINESS_DATABASE_PASSWORD=order-reference \
    "IDENTITY_ISSUER_URI=http://localhost:$IDENTITY_PORT" "IDENTITY_JWKS_URI=http://localhost:$IDENTITY_PORT/oauth2/jwks" \
    "${COMMON_KAFKA[@]}"
  wait_http monolith "http://localhost:$MONOLITH_PORT/actuator/health"
}

start_business_apps() { local app; for app in "${BUSINESS_APPS[@]}"; do "start_$app"; done; }
stop_business_apps() { local app; for app in "${BUSINESS_APPS[@]}"; do stop_app "$app"; done; }

cd "$PROJECT_ROOT"
for executable in docker curl python3 uv; do
  command -v "$executable" >/dev/null 2>&1 || { echo "Required executable is unavailable: $executable" >&2; exit 127; }
done
[[ -n "$JAVA_BIN" ]] || { echo "Required executable is unavailable: java" >&2; exit 127; }

compose down -v --remove-orphans >/dev/null 2>&1 || true
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
  uv run --project "$PROJECT_ROOT/acceptance-tests" pytest -q -s \
  "$PROJECT_ROOT/acceptance-tests/tests/test_reference_product.py" -k public_order_fulfillment_reference_product

compose stop kafka >/dev/null
REFERENCE_TOPOLOGY="$REFERENCE_TOPOLOGY_NAME" \
  python3 "$PROJECT_ROOT/scripts/reference-product/blackbox.py" create-pending "http://localhost:$GATEWAY_PORT" "$STATE_FILE"
stop_business_apps
compose start kafka >/dev/null
wait_kafka
start_business_apps
REFERENCE_TOPOLOGY="$REFERENCE_TOPOLOGY_NAME" \
  python3 "$PROJECT_ROOT/scripts/reference-product/blackbox.py" resume "http://localhost:$GATEWAY_PORT" "$STATE_FILE"

FAILED=0
echo "Reference product verification: PASS (topology=$REFERENCE_TOPOLOGY_NAME; public contract + Kafka recovery)"
if [[ "${REFERENCE_KEEP_RUNNING:-0}" == "1" ]]; then
  echo "Reference product remains available at http://localhost:$GATEWAY_PORT; press Ctrl-C to stop."
  while true; do sleep 30; done
fi
