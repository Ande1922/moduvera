#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"

"$SCRIPT_DIR/tests/test-image-contract.sh"

app_modules="apps/gateway-app,apps/identity-app,apps/catalog-app,apps/order-app,apps/inventory-app"
if [[ "${MODUVERA_IMAGE_INCLUDE_MONOLITH:-0}" == "1" ]]; then
  app_modules+=",apps/app-monolith"
fi

if [[ "${MODUVERA_IMAGE_SKIP_PACKAGE:-0}" != "1" ]]; then
  "$PROJECT_ROOT/mvnw" -q \
    -pl "$app_modules" \
    -am -DskipTests package
fi

"$SCRIPT_DIR/build-images.sh"
python3 "$SCRIPT_DIR/inspect_images.py"

if [[ "${MODUVERA_IMAGE_SKIP_SMOKE:-0}" != "1" ]]; then
  "$SCRIPT_DIR/smoke-catalog.sh"
fi

echo "Runnable App image verification: PASS"
