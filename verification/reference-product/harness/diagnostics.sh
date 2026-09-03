#!/usr/bin/env bash
set -u

TOPOLOGY="$1"
COMPOSE_PROJECT="$2"
COMPOSE_FILE="$3"
RUN_DIR="$4"

compose() { docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" "$@"; }

echo "Reference-product diagnostics for $TOPOLOGY (secrets and payloads omitted)" >&2
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
