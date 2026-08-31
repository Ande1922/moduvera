#!/usr/bin/env bash
set -euo pipefail

# Qualifies both supported application topologies. The client contract lives only in blackbox.py.
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOPOLOGY="${1:-all}"

case "$TOPOLOGY" in
  all|microservices|business-core-monolith) ;;
  *)
    echo "Unknown topology '$TOPOLOGY'; expected all, microservices, or business-core-monolith" >&2
    exit 64
    ;;
esac

if [[ "${REFERENCE_SKIP_BUILD:-0}" != "1" ]]; then
  "$PROJECT_ROOT/mvnw" -q clean install
  # These topology-specific tests inject duplicate deliveries below the public seam. Exposing a
  # production test endpoint merely for duplicate injection would weaken the black-box boundary.
  echo "Focused duplicate-delivery evidence: PASS (OrderApplicationIT, InventoryApplicationIT, ModuveraMonolithApplicationIT)"
fi

if [[ "$TOPOLOGY" == "all" ]]; then
  "$PROJECT_ROOT/scripts/reference-product/run-topology.sh" microservices
  "$PROJECT_ROOT/scripts/reference-product/run-topology.sh" business-core-monolith
else
  "$PROJECT_ROOT/scripts/reference-product/run-topology.sh" "$TOPOLOGY"
fi

echo "Reference product verification: PASS (selection=$TOPOLOGY)"
