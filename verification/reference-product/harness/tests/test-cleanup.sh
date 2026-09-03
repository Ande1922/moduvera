#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moduvera-cleanup-test.XXXXXX")"
REAL_PYTHON="$(command -v python3)"
trap 'rm -rf "$TEST_DIR"' EXIT

fail() {
  echo "cleanup test failed: $*" >&2
  exit 1
}

STUB_BIN="$TEST_DIR/bin"
mkdir "$STUB_BIN"

cat > "$STUB_BIN/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ " $* " == *" logs postgres "* ]]; then
  echo "PostgreSQL init process complete"
  exit 0
fi
if [[ " $* " == *" up "* ]]; then
  : > "$STUB_STATE_DIR/compose-started"
fi
if [[ " $* " == *" down "* ]]; then
  if [[ -f "$STUB_STATE_DIR/compose-started" ]]; then
    if [[ "${STUB_CLEANUP_MODE:-}" == "compose-failure" ]]; then
      exit 42
    fi
    if [[ "${STUB_CLEANUP_MODE:-}" == "lock-failure" ]]; then
      for lock_path in "$REFERENCE_LOCK_ROOT"/moduvera-reference-*.lock; do
        [[ -d "$lock_path" ]] || continue
        : > "$lock_path/injected-cleanup-blocker"
        break
      done
    fi
  fi
fi
exit 0
SH

cat > "$STUB_BIN/curl" <<'SH'
#!/usr/bin/env bash
exit 0
SH

cat > "$STUB_BIN/uv" <<'SH'
#!/usr/bin/env bash
if [[ "${STUB_PRIMARY_FAILURE:-0}" == "1" ]]; then
  echo "injected public-contract failure" >&2
  exit 23
fi
echo "reference acceptance: PASS (topology=${REFERENCE_TOPOLOGY}; stub contract)"
SH

cat > "$STUB_BIN/java" <<'SH'
#!/usr/bin/env bash
exit 0
SH

cat > "$STUB_BIN/python3" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == *"/blackbox.py" ]]; then
  exit 0
fi
exec "$REAL_PYTHON" "$@"
SH
chmod +x "$STUB_BIN"/*

run_case() {
  local name="$1" cleanup_mode="$2" primary_failure="$3"
  local case_dir="$TEST_DIR/$name" status
  mkdir "$case_dir" "$case_dir/locks" "$case_dir/state"
  set +e
  PATH="$STUB_BIN:$PATH" REAL_PYTHON="$REAL_PYTHON" JAVA_HOME="$case_dir/no-java" \
    STUB_STATE_DIR="$case_dir/state" STUB_CLEANUP_MODE="$cleanup_mode" \
    STUB_PRIMARY_FAILURE="$primary_failure" RUN_SLOT=50 \
    REFERENCE_PREFLIGHT_ONLY=0 REFERENCE_KEEP_RUNNING=0 \
    REFERENCE_LOCK_ROOT="$case_dir/locks" REFERENCE_PORT_MANIFEST="$case_dir/manifest.json" \
    "$HARNESS_DIR/run-topology.sh" microservices >"$case_dir/out" 2>"$case_dir/err"
  status=$?
  set -e
  printf '%s\n' "$status" > "$case_dir/status"
}

run_case success "" 0
[[ "$(<"$TEST_DIR/success/status")" == "0" ]] || fail "successful cleanup changed the run status"
[[ -z "$(find "$TEST_DIR/success/locks" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
  || fail "successful cleanup left run locks"

run_case compose-failure compose-failure 0
[[ "$(<"$TEST_DIR/compose-failure/status")" == "70" ]] \
  || fail "compose cleanup failure returned $(<"$TEST_DIR/compose-failure/status") instead of 70"
grep -F "Reference cleanup failure: docker compose down failed" \
  "$TEST_DIR/compose-failure/err" >/dev/null \
  || fail "compose cleanup failure evidence is missing"

run_case lock-failure lock-failure 0
[[ "$(<"$TEST_DIR/lock-failure/status")" == "70" ]] \
  || fail "lock cleanup failure returned $(<"$TEST_DIR/lock-failure/status") instead of 70"
grep -F "Reference cleanup failure: one or more owned run locks could not be released" \
  "$TEST_DIR/lock-failure/err" >/dev/null \
  || fail "lock cleanup failure evidence is missing"

run_case primary-preserved compose-failure 1
[[ "$(<"$TEST_DIR/primary-preserved/status")" == "23" ]] \
  || fail "cleanup failure replaced the primary failure status"
grep -F "injected public-contract failure" "$TEST_DIR/primary-preserved/err" >/dev/null \
  || fail "primary failure evidence is missing"
grep -F "Reference cleanup failure: docker compose down failed" \
  "$TEST_DIR/primary-preserved/err" >/dev/null \
  || fail "secondary cleanup failure evidence is missing"

echo "Reference cleanup tests: PASS"
