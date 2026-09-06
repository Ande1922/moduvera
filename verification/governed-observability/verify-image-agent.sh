#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
# shellcheck source=agent-runtime.sh
source "$SCRIPT_DIR/agent-runtime.sh"
AGENT="${MODUVERA_OTEL_JAVAAGENT:-}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-}"
JACOCO_AGENT="${MODUVERA_JACOCO_AGENT:-}"
EVIDENCE_DIR="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:-}"
IMAGE_REPOSITORY="${MODUVERA_IMAGE_REPOSITORY:-moduvera-local}"
IMAGE_TAG="${MODUVERA_IMAGE_TAG:-verification}"
IMAGE="$IMAGE_REPOSITORY/catalog-app:$IMAGE_TAG"
RUN_ID="governed-image-$(date +%s)-$$"
NETWORK="moduvera-$RUN_ID"
POSTGRES_CONTAINER="moduvera-$RUN_ID-postgres"
APP_CONTAINER="moduvera-$RUN_ID-catalog"
NETWORK_CREATED=0
POSTGRES_STARTED=0
APP_STARTED=0

[[ -d "$EVIDENCE_DIR" ]] || { echo "MODUVERA_OBSERVABILITY_EVIDENCE_DIR must exist" >&2; exit 64; }
[[ -f "$JACOCO_AGENT" ]] || { echo "MODUVERA_JACOCO_AGENT must identify the JaCoCo runtime Agent" >&2; exit 64; }
REFERENCE_OTEL_JAVAAGENT="$AGENT"
REFERENCE_OTEL_AGENT_EXTENSION="$EXTENSION"
REFERENCE_JACOCO_AGENT="$JACOCO_AGENT"
REFERENCE_JAVA_TOOL_OPTIONS=""
governed_agent_preflight "$AGENT" "$EXTENSION" "$JACOCO_AGENT"

cleanup() {
  local primary_status=$? cleanup_status=0
  trap - EXIT INT TERM
  set +e
  if [[ $APP_STARTED -eq 1 ]]; then docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || cleanup_status=70; fi
  if [[ $POSTGRES_STARTED -eq 1 ]]; then docker rm -f "$POSTGRES_CONTAINER" >/dev/null 2>&1 || cleanup_status=70; fi
  if [[ $NETWORK_CREATED -eq 1 ]]; then docker network rm "$NETWORK" >/dev/null 2>&1 || cleanup_status=70; fi
  if [[ $primary_status -ne 0 ]]; then exit "$primary_status"; fi
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker network create "$NETWORK" >/dev/null
NETWORK_CREATED=1
docker run --detach --name "$POSTGRES_CONTAINER" --network "$NETWORK" \
  --env POSTGRES_USER=catalog --env POSTGRES_PASSWORD=governed-image \
  --env POSTGRES_DB=catalog postgres:18.6 >/dev/null
POSTGRES_STARTED=1
deadline=$((SECONDS + 90))
until docker exec "$POSTGRES_CONTAINER" pg_isready -U catalog -d catalog >/dev/null 2>&1; do
  (( SECONDS < deadline )) || { echo "Governed image PostgreSQL did not become ready" >&2; exit 1; }
  sleep 1
done

RUNTIME_AGENT=/opt/moduvera-agent/opentelemetry-javaagent.jar
RUNTIME_EXTENSION=/opt/moduvera-agent/governed-extension.jar
RUNTIME_JACOCO=/opt/moduvera-agent/jacocoagent.jar
JAVA_OPTIONS="$(governed_agent_java_options catalog-image "$RUNTIME_AGENT" "$RUNTIME_EXTENSION")"
JAVA_OPTIONS="$JAVA_OPTIONS -javaagent:$RUNTIME_JACOCO=destfile=/tmp/governed-jacoco.exec,append=false"
docker run --detach --name "$APP_CONTAINER" --network "$NETWORK" \
  --publish 127.0.0.1::8082 \
  --mount "type=bind,source=$AGENT,target=$RUNTIME_AGENT,readonly" \
  --mount "type=bind,source=$EXTENSION,target=$RUNTIME_EXTENSION,readonly" \
  --mount "type=bind,source=$JACOCO_AGENT,target=$RUNTIME_JACOCO,readonly" \
  --env "JAVA_TOOL_OPTIONS=$JAVA_OPTIONS" \
  --env CATALOG_PORT=8082 \
  --env "CATALOG_DB_URL=jdbc:postgresql://$POSTGRES_CONTAINER:5432/catalog" \
  --env CATALOG_DB_USERNAME=catalog --env CATALOG_DB_PASSWORD=governed-image \
  --env MODUVERA_DATABASE_MIGRATION_MODE=startup \
  --env MODUVERA_DATABASE_MIGRATION_INITIALIZE=true \
  --env IDENTITY_ISSUER_URI=http://identity.invalid \
  --env IDENTITY_JWKS_URI=http://identity.invalid/oauth2/jwks \
  "$IMAGE" >/dev/null
APP_STARTED=1

binding="$(docker port "$APP_CONTAINER" 8082/tcp | tail -n 1)"
host_port="${binding##*:}"
deadline=$((SECONDS + 120))
until curl --fail --silent --max-time 2 "http://127.0.0.1:$host_port/actuator/health" >/dev/null 2>&1; do
  if ! docker inspect --format '{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null | grep -qx true; then
    docker logs "$APP_CONTAINER" >&2
    echo "Governed catalog image stopped before becoming healthy" >&2
    exit 1
  fi
  (( SECONDS < deadline )) || { docker logs "$APP_CONTAINER" >&2; exit 1; }
  sleep 1
done

[[ "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/opt/moduvera-agent/opentelemetry-javaagent.jar"}}{{.RW}}{{end}}{{end}}' "$APP_CONTAINER")" == "false" ]] \
  || { echo "OpenTelemetry Agent mount is not read-only" >&2; exit 1; }
[[ "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/opt/moduvera-agent/governed-extension.jar"}}{{.RW}}{{end}}{{end}}' "$APP_CONTAINER")" == "false" ]] \
  || { echo "governed Agent extension mount is not read-only" >&2; exit 1; }
docker exec "$APP_CONTAINER" sh -ec \
  'options="$(tr "\000" "\n" </proc/1/environ)"; \
   printf "%s\n" "$options" | grep -F -- "-javaagent:/opt/moduvera-agent/opentelemetry-javaagent.jar" >/dev/null; \
   printf "%s\n" "$options" | grep -F -- "-Dotel.javaagent.extensions=/opt/moduvera-agent/governed-extension.jar" >/dev/null'
http_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 \
  "http://127.0.0.1:$host_port/internal/api/v1/catalog/products/1?governed-image-query=must-not-export")"
[[ "$http_status" == "401" ]] || { echo "Governed image HTTP returned $http_status, expected 401" >&2; exit 1; }

docker logs "$APP_CONTAINER" > "$EVIDENCE_DIR/image-container.log" 2>&1
grep -Fq 'Governed OpenTelemetry Agent extension: ACTIVE' "$EVIDENCE_DIR/image-container.log" \
  || { echo "governed Agent extension did not report active" >&2; exit 1; }
grep -Fq 'Governed OpenTelemetry Agent extension handshake: PASS' "$EVIDENCE_DIR/image-container.log" \
  || { echo "governed Agent extension handshake did not pass" >&2; exit 1; }
docker stop --time 15 "$APP_CONTAINER" >/dev/null
docker cp "$APP_CONTAINER:/tmp/governed-jacoco.exec" "$EVIDENCE_DIR/image-jacoco.exec" >/dev/null
[[ -s "$EVIDENCE_DIR/image-jacoco.exec" ]] || { echo "JaCoCo Agent produced no execution data" >&2; exit 1; }
docker inspect "$APP_CONTAINER" > "$EVIDENCE_DIR/image-container-inspect.json"

echo "Governed image Agent smoke: PASS (external read-only OTel Agent/extension + JaCoCo + real PostgreSQL/HTTP)"
