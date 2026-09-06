#!/usr/bin/env bash
set -euo pipefail

# Explicit high-cost scenario: run both retained topology contracts concurrently without duplicating them.
HARNESS_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$HARNESS_DIR/../../.." && pwd)"
MICROSERVICES_SLOT="${1:-${REFERENCE_PARALLEL_MICROSERVICES_SLOT:-40}}"
MONOLITH_SLOT="${2:-${REFERENCE_PARALLEL_MONOLITH_SLOT:-41}}"

if [[ "${REFERENCE_KEEP_RUNNING:-0}" == "1" || "${REFERENCE_PREFLIGHT_ONLY:-0}" == "1" ]]; then
  echo "Parallel reference scenario does not support KEEP_RUNNING or PREFLIGHT_ONLY" >&2
  exit 64
fi

for override in \
  REFERENCE_POSTGRES_PORT REFERENCE_KAFKA_PORT REFERENCE_GATEWAY_PORT REFERENCE_IDENTITY_PORT \
  REFERENCE_CATALOG_PORT REFERENCE_ORDER_PORT REFERENCE_INVENTORY_PORT REFERENCE_MONOLITH_PORT \
  REFERENCE_GATEWAY_DEBUG_PORT REFERENCE_IDENTITY_DEBUG_PORT REFERENCE_CATALOG_DEBUG_PORT \
  REFERENCE_ORDER_DEBUG_PORT REFERENCE_INVENTORY_DEBUG_PORT REFERENCE_MONOLITH_DEBUG_PORT \
  REFERENCE_COMPOSE_PROJECT REFERENCE_PORT_MANIFEST; do
  if [[ -n "${!override:-}" ]]; then
    echo "Parallel reference scenario owns deterministic resources; unset $override" >&2
    exit 64
  fi
done

canonical_slot() (
  # shellcheck source=port-plan.sh
  source "$HARNESS_DIR/port-plan.sh"
  RUN_SLOT="$1"
  reference_configure_port_plan "$2"
  printf '%s\n' "$RUN_SLOT"
)

MICROSERVICES_SLOT="$(canonical_slot "$MICROSERVICES_SLOT" microservices)"
MONOLITH_SLOT="$(canonical_slot "$MONOLITH_SLOT" business-core-monolith)"
if [[ "$MICROSERVICES_SLOT" == "$MONOLITH_SLOT" ]]; then
  echo "Parallel reference scenario requires two distinct run slots" >&2
  exit 64
fi

"$HARNESS_DIR/tests/test-port-plan.sh"
"$HARNESS_DIR/tests/test-cleanup.sh"
"$HARNESS_DIR/tests/test-parallel-scenario.sh"
if [[ "${REFERENCE_SKIP_BUILD:-0}" != "1" ]]; then
  "$PROJECT_ROOT/mvnw" -q clean install -Dmonolith.skipITs=false
fi

exec python3 "$HARNESS_DIR/parallel_supervisor.py" \
  --runner "$HARNESS_DIR/run-topology.sh" \
  --validator "$HARNESS_DIR/validate-parallel-scenario.py" \
  "$MICROSERVICES_SLOT" "$MONOLITH_SLOT"
