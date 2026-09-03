#!/usr/bin/env bash
set -euo pipefail

IMAGE_REPOSITORY="${MODUVERA_IMAGE_REPOSITORY:-moduvera-local}"
IMAGE_TAG="${MODUVERA_IMAGE_TAG:-verification}"
IMAGE="$IMAGE_REPOSITORY/catalog-app:$IMAGE_TAG"
MONOLITH_IMAGE="$IMAGE_REPOSITORY/app-monolith:$IMAGE_TAG"
RUN_ID="image-smoke-$(date +%s)-$$"
NETWORK="moduvera-$RUN_ID"
POSTGRES_CONTAINER="moduvera-$RUN_ID-postgres"
APP_CONTAINER="moduvera-$RUN_ID-catalog"
DEBUG_CONTAINER="moduvera-$RUN_ID-catalog-debug"
MONOLITH_CONTAINER="moduvera-$RUN_ID-monolith"
APP_PORT=18082
DEBUG_PORT=5005
NETWORK_CREATED=0
POSTGRES_STARTED=0
APP_STARTED=0
DEBUG_STARTED=0
MONOLITH_STARTED=0

cleanup() {
  local primary_status=$? cleanup_status=0
  trap - EXIT INT TERM
  set +e
  if [[ $MONOLITH_STARTED -eq 1 ]]; then
    docker rm -f "$MONOLITH_CONTAINER" >/dev/null 2>&1 || cleanup_status=70
  fi
  if [[ $DEBUG_STARTED -eq 1 ]]; then
    docker rm -f "$DEBUG_CONTAINER" >/dev/null 2>&1 || cleanup_status=70
  fi
  if [[ $APP_STARTED -eq 1 ]]; then
    docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || cleanup_status=70
  fi
  if [[ $POSTGRES_STARTED -eq 1 ]]; then
    docker rm -f "$POSTGRES_CONTAINER" >/dev/null 2>&1 || cleanup_status=70
  fi
  if [[ $NETWORK_CREATED -eq 1 ]]; then
    docker network rm "$NETWORK" >/dev/null 2>&1 || cleanup_status=70
  fi
  if [[ $primary_status -ne 0 ]]; then exit "$primary_status"; fi
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker network create "$NETWORK" >/dev/null
NETWORK_CREATED=1
docker run --detach --name "$POSTGRES_CONTAINER" --network "$NETWORK" \
  --env POSTGRES_USER=catalog \
  --env POSTGRES_PASSWORD=image-smoke \
  --env POSTGRES_DB=catalog \
  postgres:18.6 >/dev/null
POSTGRES_STARTED=1

deadline=$((SECONDS + 90))
until docker exec "$POSTGRES_CONTAINER" pg_isready -U catalog -d catalog >/dev/null 2>&1; do
  if (( SECONDS >= deadline )); then
    echo "Catalog image smoke PostgreSQL did not become ready" >&2
    exit 1
  fi
  sleep 1
done

docker run --detach --name "$APP_CONTAINER" --network "$NETWORK" \
  --publish "127.0.0.1::$APP_PORT" \
  --env "CATALOG_PORT=$APP_PORT" \
  --env "CATALOG_DB_URL=jdbc:postgresql://$POSTGRES_CONTAINER:5432/catalog" \
  --env CATALOG_DB_USERNAME=catalog \
  --env CATALOG_DB_PASSWORD=image-smoke \
  --env MODUVERA_DATABASE_MIGRATION_MODE=startup \
  --env MODUVERA_DATABASE_MIGRATION_INITIALIZE=true \
  --env IDENTITY_ISSUER_URI=http://identity.invalid \
  --env IDENTITY_JWKS_URI=http://identity.invalid/oauth2/jwks \
  "$IMAGE" >/dev/null
APP_STARTED=1

app_binding="$(docker port "$APP_CONTAINER" "$APP_PORT/tcp" | tail -n 1)"
app_host_port="${app_binding##*:}"

deadline=$((SECONDS + 120))
while true; do
  if health="$(curl --fail --silent --max-time 2 \
      "http://127.0.0.1:$app_host_port/actuator/health" 2>/dev/null)"; then
    break
  fi
  if ! docker inspect --format '{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null | grep -qx true; then
    docker logs "$APP_CONTAINER" >&2
    echo "Catalog image stopped before becoming healthy" >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    docker logs "$APP_CONTAINER" >&2
    echo "Catalog image did not become healthy" >&2
    exit 1
  fi
  sleep 1
done

grep -q '"status":"UP"' <<<"$health" || {
  echo "Catalog image health response was not UP: $health" >&2
  exit 1
}
http_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 2 \
  "http://127.0.0.1:$app_host_port/internal/api/v1/catalog/products/1")"
[[ "$http_status" == "401" ]] || {
  echo "Catalog unauthenticated HTTP smoke returned $http_status, expected 401" >&2
  exit 1
}
[[ "$(docker exec "$APP_CONTAINER" id -u)" == "10001" ]] || {
  echo "Catalog image process is not running with uid 10001" >&2
  exit 1
}
[[ "$(docker exec "$APP_CONTAINER" id -g)" == "10001" ]] || {
  echo "Catalog image process is not running with gid 10001" >&2
  exit 1
}
if docker exec "$APP_CONTAINER" sh -ec \
    'tr "\000" "\n" </proc/1/cmdline | grep -qi jdwp'; then
  echo "JDWP unexpectedly appears in the application process arguments" >&2
  exit 1
fi
if docker exec "$APP_CONTAINER" awk \
    '$2 ~ /:138D$/ && $4 == "0A" { found = 1 } END { exit !found }' \
    /proc/net/tcp /proc/net/tcp6; then
  echo "JDWP unexpectedly listens when no runtime JVM option was supplied" >&2
  exit 1
fi

docker run --detach --name "$MONOLITH_CONTAINER" --network "$NETWORK" \
  --publish "127.0.0.1::8083" \
  --env "BUSINESS_DATABASE_URL=jdbc:postgresql://$POSTGRES_CONTAINER:5432/catalog" \
  --env BUSINESS_DATABASE_USERNAME=catalog \
  --env BUSINESS_DATABASE_PASSWORD=image-smoke \
  --env MODUVERA_DATABASE_MIGRATION_MODE=startup \
  --env MODUVERA_DATABASE_MIGRATION_INITIALIZE=true \
  --env MODUVERA_IDENTIFIER_WORKER_ID=7 \
  --env KAFKA_BROKERS=127.0.0.1:1 \
  --env INVENTORY_RESERVE_TOPIC=image-smoke-inventory-reserve \
  --env INVENTORY_RESULT_TOPIC=image-smoke-inventory-result \
  --env IDENTITY_ISSUER_URI=http://identity.invalid \
  --env IDENTITY_JWKS_URI=http://identity.invalid/oauth2/jwks \
  "$MONOLITH_IMAGE" \
  --spring.cloud.function.definition= \
  --spring.cloud.stream.function.autodetect=false \
  --spring.cloud.stream.bindings.reserveInventory-in-0.consumer.autoStartup=false \
  --spring.cloud.stream.bindings.inventoryResult-in-0.consumer.autoStartup=false >/dev/null
MONOLITH_STARTED=1

monolith_binding="$(docker port "$MONOLITH_CONTAINER" 8083/tcp | tail -n 1)"
[[ "$monolith_binding" == 127.0.0.1:* ]] || {
  echo "Monolith default port 8083 was not mapped to loopback" >&2
  exit 1
}
deadline=$((SECONDS + 120))
until docker exec "$MONOLITH_CONTAINER" awk \
    '$2 ~ /:1F93$/ && $4 == "0A" { found = 1 } END { exit !found }' \
    /proc/net/tcp /proc/net/tcp6; do
  if ! docker inspect --format '{{.State.Running}}' "$MONOLITH_CONTAINER" 2>/dev/null | grep -qx true; then
    docker logs --tail 100 "$MONOLITH_CONTAINER" >&2
    echo "Monolith image stopped before listening on its default port 8083" >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    docker logs --tail 100 "$MONOLITH_CONTAINER" >&2
    echo "Monolith image did not listen on its default port 8083" >&2
    exit 1
  fi
  sleep 1
done

docker run --detach --name "$DEBUG_CONTAINER" --network "$NETWORK" \
  --publish "127.0.0.1::$DEBUG_PORT" \
  --env "JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$DEBUG_PORT" \
  --env "CATALOG_PORT=$APP_PORT" \
  --env "CATALOG_DB_URL=jdbc:postgresql://$POSTGRES_CONTAINER:5432/catalog" \
  --env CATALOG_DB_USERNAME=catalog \
  --env CATALOG_DB_PASSWORD=image-smoke \
  --env MODUVERA_DATABASE_MIGRATION_MODE=startup \
  --env MODUVERA_DATABASE_MIGRATION_INITIALIZE=true \
  --env IDENTITY_ISSUER_URI=http://identity.invalid \
  --env IDENTITY_JWKS_URI=http://identity.invalid/oauth2/jwks \
  "$IMAGE" >/dev/null
DEBUG_STARTED=1

debug_binding="$(docker port "$DEBUG_CONTAINER" "$DEBUG_PORT/tcp" | tail -n 1)"
debug_host_port="${debug_binding##*:}"
deadline=$((SECONDS + 30))
until python3 - "$debug_host_port" <<'PY'
import socket
import sys

with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=1) as connection:
    connection.sendall(b"JDWP-Handshake")
    response = connection.recv(len(b"JDWP-Handshake"))
    if response != b"JDWP-Handshake":
        raise SystemExit(1)
PY
do
  if ! docker inspect --format '{{.State.Running}}' "$DEBUG_CONTAINER" 2>/dev/null | grep -qx true; then
    docker logs "$DEBUG_CONTAINER" >&2
    echo "Catalog image stopped before runtime-enabled JDWP became reachable" >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    docker logs "$DEBUG_CONTAINER" >&2
    echo "Runtime-enabled JDWP did not become reachable" >&2
    exit 1
  fi
  sleep 1
done

echo "Runnable App smoke PASS: Catalog non-root/real PostgreSQL/port override/health/HTTP/JDWP; Monolith default port 8083 listening and mapped"
