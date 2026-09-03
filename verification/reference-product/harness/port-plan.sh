#!/usr/bin/env bash

# Shared deterministic port planning for the disposable reference-product harness.
# This file is sourced by run-topology.sh and can also be executed for static verification.

REFERENCE_PORT_STRIDE=100
REFERENCE_LOCK_OWNER="${REFERENCE_LOCK_OWNER:-}"
REFERENCE_LOCK_PID="${REFERENCE_LOCK_PID:-}"
REFERENCE_OWNED_LOCK_PATHS=()

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
  local topology="$1" label port i j

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
    for ((j = 0; j < i; j++)); do
      if [[ "${REFERENCE_REQUIRED_PORTS[$i]}" == "${REFERENCE_REQUIRED_PORTS[$j]}" ]]; then
        reference_fail "Required ports overlap in the plan: ${REFERENCE_REQUIRED_PORT_LABELS[$j]} and ${REFERENCE_REQUIRED_PORT_LABELS[$i]} both use ${REFERENCE_REQUIRED_PORTS[$i]}"
        return
      fi
    done
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

reference_json_quote() {
  python3 -c 'import json, sys; print(json.dumps(sys.argv[1]), end="")' "$1"
}

reference_write_port_manifest() {
  local topology="$1" manifest_path="$2" app app_port debug_port first=1
  local manifest_parent
  manifest_parent="$(dirname "$manifest_path")"
  [[ -d "$manifest_parent" ]] || {
    reference_fail "Port manifest parent directory does not exist: $manifest_parent"
    return
  }
  if [[ -n "${REFERENCE_MANIFEST_RUN_ID:-}" ]] && ! command -v python3 >/dev/null 2>&1; then
    echo "Required executable is unavailable: python3" >&2
    return 127
  fi

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
    printf '}},"debugEnabled":%s' "$([[ "$REFERENCE_DEBUG" == "1" ]] && echo true || echo false)"
    if [[ -n "${REFERENCE_MANIFEST_RUN_ID:-}" ]]; then
      printf ',"resources":{"runId":%s,"composeProject":%s,"dataNamespace":%s,"tempDirectory":%s,"topics":{' \
        "$(reference_json_quote "$REFERENCE_MANIFEST_RUN_ID")" \
        "$(reference_json_quote "$REFERENCE_MANIFEST_COMPOSE_PROJECT")" \
        "$(reference_json_quote "$REFERENCE_MANIFEST_COMPOSE_PROJECT")" \
        "$(reference_json_quote "$REFERENCE_MANIFEST_RUN_DIRECTORY")"
      printf '"inventoryReserve":%s,"inventoryResult":%s}}' \
        "$(reference_json_quote "$REFERENCE_MANIFEST_INVENTORY_RESERVE_TOPIC")" \
        "$(reference_json_quote "$REFERENCE_MANIFEST_INVENTORY_RESULT_TOPIC")"
    fi
    printf '}\n'
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

reference_prepare_locking() {
  REFERENCE_LOCK_ROOT="${REFERENCE_LOCK_ROOT:-${REFERENCE_SLOT_LOCK_ROOT:-${TMPDIR:-/tmp}}}"
  if [[ ! -e "$REFERENCE_LOCK_ROOT" ]]; then
    echo "Reference lock root is missing: $REFERENCE_LOCK_ROOT" >&2
    return 72
  fi
  if [[ ! -d "$REFERENCE_LOCK_ROOT" ]]; then
    echo "Reference lock root is not a directory: $REFERENCE_LOCK_ROOT" >&2
    return 72
  fi
  if [[ ! -w "$REFERENCE_LOCK_ROOT" ]]; then
    echo "Reference lock root is not writable: $REFERENCE_LOCK_ROOT" >&2
    return 73
  fi

  REFERENCE_LOCK_PID="${REFERENCE_LOCK_PID:-$$}"
  REFERENCE_LOCK_OWNER="${REFERENCE_LOCK_OWNER:-$REFERENCE_LOCK_PID-$(date +%s)-$RANDOM}"
  if [[ ! "$REFERENCE_LOCK_PID" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid reference lock PID: $REFERENCE_LOCK_PID" >&2
    return 64
  fi
  if [[ ! "$REFERENCE_LOCK_OWNER" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "Invalid reference lock owner token" >&2
    return 64
  fi
  REFERENCE_OWNED_LOCK_PATHS=()
}

reference_pid_is_active() {
  local pid="$1"
  kill -0 "$pid" 2>/dev/null && return 0
  ps -p "$pid" -o pid= 2>/dev/null | grep -q '[0-9]'
}

reference_record_owned_lock() {
  local lock_path="$1" owner_file
  owner_file="$lock_path/owner"
  if ! chmod 700 "$lock_path" || ! (umask 077; printf '%s:%s\n' "$REFERENCE_LOCK_PID" "$REFERENCE_LOCK_OWNER" > "$owner_file"); then
    rm -f "$owner_file"
    rmdir "$lock_path" 2>/dev/null || true
    echo "Unable to record ownership for reference lock: $lock_path" >&2
    return 73
  fi
  REFERENCE_OWNED_LOCK_PATHS+=("$lock_path")
}

reference_remove_stale_tombstone() {
  local tombstone="$1"
  rm -f "$tombstone/owner" "$tombstone/pid"
  if ! rmdir "$tombstone" 2>/dev/null; then
    echo "Reclaimed lock left a non-empty tombstone for manual inspection: $tombstone" >&2
  fi
}

reference_acquire_named_lock() {
  local lock_name="$1" description="$2" lock_path owner_file legacy_pid_file
  local owner_record owner_pid owner_metadata_file tombstone attempt
  lock_path="$REFERENCE_LOCK_ROOT/moduvera-reference-$lock_name.lock"
  owner_file="$lock_path/owner"
  legacy_pid_file="$lock_path/pid"

  for attempt in 1 2 3; do
    if mkdir "$lock_path" 2>/dev/null; then
      reference_record_owned_lock "$lock_path"
      return
    fi

    if [[ ! -e "$lock_path" ]]; then
      echo "Unable to create $description lock in writable root $REFERENCE_LOCK_ROOT" >&2
      return 73
    fi
    if [[ ! -d "$lock_path" ]]; then
      echo "$description lock has missing ownership metadata; refusing to reclaim: $lock_path" >&2
      return 73
    fi

    if [[ -f "$owner_file" ]]; then
      owner_metadata_file="$owner_file"
      owner_record="$(<"$owner_file")"
      owner_pid="${owner_record%%:*}"
      if [[ ! "$owner_record" =~ ^[1-9][0-9]*:.+ || "$owner_pid" == "$owner_record" ]]; then
        echo "$description lock has invalid ownership metadata; refusing to reclaim: $lock_path" >&2
        return 73
      fi
    elif [[ -f "$legacy_pid_file" ]]; then
      owner_metadata_file="$legacy_pid_file"
      owner_record="$(<"$legacy_pid_file")"
      owner_pid="$owner_record"
      if [[ ! "$owner_pid" =~ ^[1-9][0-9]*$ ]]; then
        echo "$description lock has invalid legacy PID metadata; refusing to reclaim: $lock_path" >&2
        return 73
      fi
    else
      echo "$description lock has missing ownership metadata; refusing to reclaim: $lock_path" >&2
      return 73
    fi
    if [[ ! "$owner_pid" =~ ^[1-9][0-9]*$ ]]; then
      echo "$description lock has invalid ownership metadata; refusing to reclaim: $lock_path" >&2
      return 73
    fi
    if reference_pid_is_active "$owner_pid"; then
      echo "$description lock is active (pid=$owner_pid): $lock_path" >&2
      return 73
    fi

    tombstone="$lock_path.stale.$REFERENCE_LOCK_OWNER.$attempt"
    if mv "$lock_path" "$tombstone" 2>/dev/null; then
      owner_metadata_file="$tombstone/$(basename "$owner_metadata_file")"
      if [[ ! -f "$owner_metadata_file" || "$(<"$owner_metadata_file")" != "$owner_record" ]]; then
        echo "$description lock ownership changed during stale-lock recovery; refusing to reclaim" >&2
        mv "$tombstone" "$lock_path" 2>/dev/null || true
        return 73
      fi
      echo "Reclaiming stale $description lock owned by dead pid $owner_pid: $lock_path" >&2
      reference_remove_stale_tombstone "$tombstone"
    fi
  done

  echo "Unable to acquire $description lock after concurrent stale-lock recovery: $lock_path" >&2
  return 73
}

reference_sort_required_ports() {
  local i j key
  REFERENCE_SORTED_REQUIRED_PORTS=("${REFERENCE_REQUIRED_PORTS[@]}")
  for ((i = 1; i < ${#REFERENCE_SORTED_REQUIRED_PORTS[@]}; i++)); do
    key="${REFERENCE_SORTED_REQUIRED_PORTS[$i]}"
    j=$((i - 1))
    while (( j >= 0 )); do
      if (( REFERENCE_SORTED_REQUIRED_PORTS[$j] <= key )); then
        break
      fi
      REFERENCE_SORTED_REQUIRED_PORTS[$((j + 1))]="${REFERENCE_SORTED_REQUIRED_PORTS[$j]}"
      j=$((j - 1))
    done
    REFERENCE_SORTED_REQUIRED_PORTS[$((j + 1))]="$key"
  done
}

reference_acquire_run_locks() {
  local port
  reference_prepare_locking || return
  reference_acquire_named_lock "slot-$RUN_SLOT" "Reference run slot $RUN_SLOT" || return
  reference_sort_required_ports || return
  for port in "${REFERENCE_SORTED_REQUIRED_PORTS[@]}"; do
    reference_acquire_named_lock "port-$port" "Required host port $port" || return
  done
}

reference_release_run_locks() {
  local i lock_path owner_file expected_owner
  [[ -n "${REFERENCE_LOCK_OWNER:-}" ]] || return 0
  expected_owner="${REFERENCE_LOCK_PID:-}:${REFERENCE_LOCK_OWNER:-}"
  for ((i = ${#REFERENCE_OWNED_LOCK_PATHS[@]} - 1; i >= 0; i--)); do
    lock_path="${REFERENCE_OWNED_LOCK_PATHS[$i]}"
    owner_file="$lock_path/owner"
    if [[ -f "$owner_file" && "$(<"$owner_file")" == "$expected_owner" ]]; then
      rm -f "$owner_file"
      rmdir "$lock_path" 2>/dev/null || true
    elif [[ -e "$lock_path" ]]; then
      echo "Reference lock ownership changed; refusing to release: $lock_path" >&2
    fi
  done
  REFERENCE_OWNED_LOCK_PATHS=()
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
