#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OBSERVABILITY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=../login-fixture-env.sh
source "$OBSERVABILITY_DIR/login-fixture-env.sh"

fail() { echo "login fixture environment test failed: $*" >&2; exit 1; }
digest() { printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; }

fixture_username="fixture-user-$(python3 -c 'import secrets; print(secrets.token_hex(8))')"
fixture_credential="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
fixture_tenant="tenant-a"
export LOGIN_USERNAME="collision-user"
export LOGIN_CREDENTIAL="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
export LOGIN_TENANT="collision-tenant"
export MODUVERA_OBSERVABILITY_LOGIN_USERNAME="$fixture_username"
export MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL="$fixture_credential"
export MODUVERA_OBSERVABILITY_LOGIN_TENANT="$fixture_tenant"

capture_login_fixture_environment
require_login_fixture_environment

for variable in LOGIN_USERNAME LOGIN_CREDENTIAL LOGIN_TENANT \
  MODUVERA_OBSERVABILITY_LOGIN_USERNAME MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL \
  MODUVERA_OBSERVABILITY_LOGIN_TENANT; do
  env | grep -q "^$variable=" && fail "$variable leaked to an unrelated child"
done

expected_snapshot="$(digest "$fixture_username")|$(digest "$fixture_credential")|$(digest "$fixture_tenant")"
for probe_phase in traffic outage; do
  actual_snapshot="$(run_login_probe python3 -c '
import hashlib
import os
names = (
    "MODUVERA_OBSERVABILITY_LOGIN_USERNAME",
    "MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL",
    "MODUVERA_OBSERVABILITY_LOGIN_TENANT",
)
print("|".join(hashlib.sha256(os.environ[name].encode()).hexdigest() for name in names))
')"
  [[ "$actual_snapshot" == "$expected_snapshot" ]] \
    || fail "$probe_phase probe did not receive the captured fixture environment"
done

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
set +e
preflight_output="$(
  LOGIN_USERNAME=collision-user \
  LOGIN_CREDENTIAL="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')" \
  LOGIN_TENANT=collision-tenant \
  MODUVERA_OBSERVABILITY_LOGIN_USERNAME="$fixture_username" \
  MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL="$fixture_credential" \
  MODUVERA_OBSERVABILITY_LOGIN_TENANT=tenant-b \
  MODUVERA_OBSERVABILITY_EVIDENCE_DIR="$test_root/evidence" \
  MODUVERA_OBSERVABILITY_MAVEN_REPO="$test_root/m2" \
  "$OBSERVABILITY_DIR/verify.sh" 2>&1
)"
preflight_exit=$?
set -e
[[ "$preflight_exit" -eq 64 ]] || fail "noncanonical tenant preflight exited $preflight_exit"
[[ ! -e "$test_root/evidence" && ! -e "$test_root/m2" ]] \
  || fail "noncanonical tenant reached an expensive launcher side effect"
grep -Fq 'tenant-a' <<<"$preflight_output" || fail "noncanonical tenant diagnostic is missing"

echo "Login fixture environment contract: PASS"
