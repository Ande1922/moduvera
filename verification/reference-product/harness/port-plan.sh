#!/usr/bin/env bash

# Shared deterministic port planning for the disposable reference-product harness.
# This file is sourced by run-topology.sh and can also be executed for static verification.

REFERENCE_PORT_STRIDE=100

reference_fail() {
  echo "$*" >&2
  return 64
}

reference_decimal() {
  local value="$1"
  echo $((10#$value))
}

reference_validate_port() {
  local label="$1" value="$2" decimal
  if [[ ! "$value" =~ ^[0-9]+$ || ${#value} -gt 5 ]]; then
    reference_fail "Invalid $label port '$value'; expected an integer from 1 through 65535"
    return
  fi
  decimal="$(reference_decimal "$value")"
  if (( decimal < 1 || decimal > 65535 )); then
    reference_fail "Invalid $label port '$value'; expected an integer from 1 through 65535"
    return
  fi
}

reference_derived_port() {
  local environment_name="$1" base="$2" configured
  configured="${!environment_name:-}"
  if [[ -n "$configured" ]]; then
    echo "$configured"
  else
    echo $((base + RUN_SLOT * REFERENCE_PORT_STRIDE))
  fi
}

reference_configure_port_plan() {
  local topology="$1" label port i

  case "$topology" in
    microservices|business-core-monolith) ;;
    *) reference_fail "Unknown topology '$topology'; expected microservices or business-core-monolith"; return ;;
  esac

  RUN_SLOT="${RUN_SLOT:-0}"
  if [[ ! "$RUN_SLOT" =~ ^(0|[1-9][0-9]*)$ ]]; then
    reference_fail "Invalid RUN_SLOT '$RUN_SLOT'; expected a non-negative decimal integer"
    return
  fi
  if (( ${#RUN_SLOT} > 2 )); then
    reference_fail "Invalid RUN_SLOT '$RUN_SLOT'; derived ports exceed 65535"
    return
  fi
  RUN_SLOT="$(reference_decimal "$RUN_SLOT")"

  REFERENCE_DEBUG="${REFERENCE_DEBUG:-0}"
  if [[ "$REFERENCE_DEBUG" != "0" && "$REFERENCE_DEBUG" != "1" ]]; then
    reference_fail "Invalid REFERENCE_DEBUG '$REFERENCE_DEBUG'; expected 0 or 1"
    return
  fi

  POSTGRES_PORT="$(reference_derived_port REFERENCE_POSTGRES_PORT 55432)"
  KAFKA_PORT="$(reference_derived_port REFERENCE_KAFKA_PORT 59092)"
  GATEWAY_PORT="$(reference_derived_port REFERENCE_GATEWAY_PORT 58080)"
  IDENTITY_PORT="$(reference_derived_port REFERENCE_IDENTITY_PORT 58081)"
  CATALOG_PORT="$(reference_derived_port REFERENCE_CATALOG_PORT 58082)"
  ORDER_PORT="$(reference_derived_port REFERENCE_ORDER_PORT 58083)"
  INVENTORY_PORT="$(reference_derived_port REFERENCE_INVENTORY_PORT 58084)"
  MONOLITH_PORT="$(reference_derived_port REFERENCE_MONOLITH_PORT 58085)"

  GATEWAY_DEBUG_PORT="$(reference_derived_port REFERENCE_GATEWAY_DEBUG_PORT 50080)"
  IDENTITY_DEBUG_PORT="$(reference_derived_port REFERENCE_IDENTITY_DEBUG_PORT 50081)"
  CATALOG_DEBUG_PORT="$(reference_derived_port REFERENCE_CATALOG_DEBUG_PORT 50082)"
  ORDER_DEBUG_PORT="$(reference_derived_port REFERENCE_ORDER_DEBUG_PORT 50083)"
  INVENTORY_DEBUG_PORT="$(reference_derived_port REFERENCE_INVENTORY_DEBUG_PORT 50084)"
  MONOLITH_DEBUG_PORT="$(reference_derived_port REFERENCE_MONOLITH_DEBUG_PORT 50085)"

  if [[ "$topology" == "microservices" ]]; then
    REFERENCE_SELECTED_APPS=(gateway identity catalog order inventory)
  else
    REFERENCE_SELECTED_APPS=(gateway identity monolith)
  fi

  REFERENCE_REQUIRED_PORT_LABELS=(postgres kafka)
  REFERENCE_REQUIRED_PORTS=("$POSTGRES_PORT" "$KAFKA_PORT")
  for label in "${REFERENCE_SELECTED_APPS[@]}"; do
    port="$(reference_application_port "$label")"
    REFERENCE_REQUIRED_PORT_LABELS+=("$label")
    REFERENCE_REQUIRED_PORTS+=("$port")
    if [[ "$REFERENCE_DEBUG" == "1" ]]; then
      port="$(reference_debug_port "$label")"
      REFERENCE_REQUIRED_PORT_LABELS+=("$label-debug")
      REFERENCE_REQUIRED_PORTS+=("$port")
    fi
  done

  for ((i = 0; i < ${#REFERENCE_REQUIRED_PORTS[@]}; i++)); do
    reference_validate_port "${REFERENCE_REQUIRED_PORT_LABELS[$i]}" "${REFERENCE_REQUIRED_PORTS[$i]}" || return
  done

  export RUN_SLOT REFERENCE_DEBUG
  export REFERENCE_POSTGRES_PORT="$POSTGRES_PORT" REFERENCE_KAFKA_PORT="$KAFKA_PORT"
  export REFERENCE_GATEWAY_PORT="$GATEWAY_PORT" REFERENCE_IDENTITY_PORT="$IDENTITY_PORT"
  export REFERENCE_CATALOG_PORT="$CATALOG_PORT" REFERENCE_ORDER_PORT="$ORDER_PORT"
  export REFERENCE_INVENTORY_PORT="$INVENTORY_PORT" REFERENCE_MONOLITH_PORT="$MONOLITH_PORT"
}

reference_application_port() {
  case "$1" in
    gateway) echo "$GATEWAY_PORT" ;;
    identity) echo "$IDENTITY_PORT" ;;
    catalog) echo "$CATALOG_PORT" ;;
    order) echo "$ORDER_PORT" ;;
    inventory) echo "$INVENTORY_PORT" ;;
    monolith) echo "$MONOLITH_PORT" ;;
    *) reference_fail "Unknown reference application '$1'" ;;
  esac
}

reference_debug_port() {
  case "$1" in
    gateway) echo "$GATEWAY_DEBUG_PORT" ;;
    identity) echo "$IDENTITY_DEBUG_PORT" ;;
    catalog) echo "$CATALOG_DEBUG_PORT" ;;
    order) echo "$ORDER_DEBUG_PORT" ;;
    inventory) echo "$INVENTORY_DEBUG_PORT" ;;
    monolith) echo "$MONOLITH_DEBUG_PORT" ;;
    *) reference_fail "Unknown reference application '$1'" ;;
  esac
}

reference_write_port_manifest() {
  local topology="$1" manifest_path="$2" app app_port debug_port first=1
  local manifest_parent
  manifest_parent="$(dirname "$manifest_path")"
  [[ -d "$manifest_parent" ]] || {
    reference_fail "Port manifest parent directory does not exist: $manifest_parent"
    return
  }

  {
    printf '{"schemaVersion":1,"runSlot":%d,"portStride":%d,"topology":"%s","ports":{' \
      "$RUN_SLOT" "$REFERENCE_PORT_STRIDE" "$topology"
    printf '"postgres":%d,"kafka":%d,"apps":{' "$POSTGRES_PORT" "$KAFKA_PORT"
    for app in "${REFERENCE_SELECTED_APPS[@]}"; do
      app_port="$(reference_application_port "$app")"
      if (( first == 0 )); then printf ','; fi
      first=0
      printf '"%s":{"application":%d,"management":%d' "$app" "$app_port" "$app_port"
      if [[ "$REFERENCE_DEBUG" == "1" ]]; then
        debug_port="$(reference_debug_port "$app")"
        printf ',"debug":%d' "$debug_port"
      fi
      printf '}'
    done
    printf '}},"debugEnabled":%s}\n' "$([[ "$REFERENCE_DEBUG" == "1" ]] && echo true || echo false)"
  } > "$manifest_path"

  echo "Reference port plan: topology=$topology runSlot=$RUN_SLOT stride=$REFERENCE_PORT_STRIDE"
  echo "  postgres=$POSTGRES_PORT kafka=$KAFKA_PORT"
  for app in "${REFERENCE_SELECTED_APPS[@]}"; do
    app_port="$(reference_application_port "$app")"
    if [[ "$REFERENCE_DEBUG" == "1" ]]; then
      debug_port="$(reference_debug_port "$app")"
      echo "  $app application=$app_port management=$app_port debug=$debug_port"
    else
      echo "  $app application=$app_port management=$app_port"
    fi
  done
  echo "Reference port manifest: $manifest_path"
  echo "REFERENCE_PORT_MANIFEST_JSON=$(<"$manifest_path")"
}

reference_preflight_ports() {
  local harness_dir="$1" arguments=() i
  command -v python3 >/dev/null 2>&1 || {
    echo "Required executable is unavailable: python3" >&2
    return 127
  }
  for ((i = 0; i < ${#REFERENCE_REQUIRED_PORTS[@]}; i++)); do
    arguments+=("${REFERENCE_REQUIRED_PORT_LABELS[$i]}=${REFERENCE_REQUIRED_PORTS[$i]}")
  done
  python3 "$harness_dir/preflight_ports.py" "${arguments[@]}"
}

reference_acquire_slot() {
  local lock_root="${REFERENCE_SLOT_LOCK_ROOT:-${TMPDIR:-/tmp}}" lock_path
  lock_path="$lock_root/moduvera-reference-slot-$RUN_SLOT.lock"
  if ! mkdir "$lock_path" 2>/dev/null; then
    echo "Reference run slot $RUN_SLOT is already active (lock: $lock_path)" >&2
    return 73
  fi
  REFERENCE_SLOT_LOCK_PATH="$lock_path"
  chmod 700 "$REFERENCE_SLOT_LOCK_PATH"
  printf '%s\n' "$$" > "$REFERENCE_SLOT_LOCK_PATH/pid"
}

reference_release_slot() {
  if [[ -n "${REFERENCE_SLOT_LOCK_PATH:-}" && -d "$REFERENCE_SLOT_LOCK_PATH" ]]; then
    rm -f "$REFERENCE_SLOT_LOCK_PATH/pid"
    rmdir "$REFERENCE_SLOT_LOCK_PATH" 2>/dev/null || true
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -euo pipefail
  harness_dir="$(cd "$(dirname "$0")" && pwd)"
  topology="${1:-}"
  manifest_path="${2:-}"
  mode="${3:-}"
  [[ -n "$manifest_path" ]] || {
    echo "usage: port-plan.sh microservices|business-core-monolith MANIFEST_PATH [--preflight]" >&2
    exit 64
  }
  reference_configure_port_plan "$topology"
  reference_write_port_manifest "$topology" "$manifest_path"
  if [[ -n "$mode" && "$mode" != "--preflight" ]]; then
    echo "Unknown port-plan mode '$mode'; expected --preflight" >&2
    exit 64
  fi
  if [[ "$mode" == "--preflight" ]]; then
    reference_preflight_ports "$harness_dir"
    echo "Reference port preflight: PASS"
  fi
fi
