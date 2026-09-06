#!/usr/bin/env bash

CANONICAL_LOGIN_TENANT="tenant-a"

capture_login_fixture_environment() {
  LOGIN_USERNAME="${MODUVERA_OBSERVABILITY_LOGIN_USERNAME:-}"
  LOGIN_CREDENTIAL="${MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL:-}"
  LOGIN_TENANT="${MODUVERA_OBSERVABILITY_LOGIN_TENANT:-}"
  export -n LOGIN_USERNAME LOGIN_CREDENTIAL LOGIN_TENANT
  unset MODUVERA_OBSERVABILITY_LOGIN_USERNAME \
    MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL MODUVERA_OBSERVABILITY_LOGIN_TENANT
}

require_login_fixture_environment() {
  [[ -n "$LOGIN_USERNAME" ]] || {
    echo "MODUVERA_OBSERVABILITY_LOGIN_USERNAME is required for the local reference login fixture" >&2
    return 64
  }
  [[ -n "$LOGIN_CREDENTIAL" ]] || {
    echo "MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL is required for the local reference login fixture" >&2
    return 64
  }
  [[ -n "$LOGIN_TENANT" ]] || {
    echo "MODUVERA_OBSERVABILITY_LOGIN_TENANT is required for the local reference login fixture" >&2
    return 64
  }
  [[ "$LOGIN_TENANT" == "$CANONICAL_LOGIN_TENANT" ]] || {
    echo "MODUVERA_OBSERVABILITY_LOGIN_TENANT must select the canonical tenant-a qualification fixture" >&2
    return 64
  }
}

run_login_probe() {
  MODUVERA_OBSERVABILITY_LOGIN_USERNAME="$LOGIN_USERNAME" \
    MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL="$LOGIN_CREDENTIAL" \
    MODUVERA_OBSERVABILITY_LOGIN_TENANT="$LOGIN_TENANT" \
    "$@"
}
