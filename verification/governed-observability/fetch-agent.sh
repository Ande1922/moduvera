#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
# shellcheck source=agent.lock
source "$SCRIPT_DIR/agent.lock"
DESTINATION="${1:-}"

if [[ -z "$DESTINATION" ]]; then
  echo "usage: fetch-agent.sh <destination-file>" >&2
  exit 64
fi
if [[ -d "$DESTINATION" ]]; then
  DESTINATION="$DESTINATION/opentelemetry-javaagent-$OTEL_JAVAAGENT_VERSION.jar"
fi
DESTINATION_PARENT="$(dirname "$DESTINATION")"
[[ -d "$DESTINATION_PARENT" ]] || {
  echo "Agent destination parent does not exist: $DESTINATION_PARENT" >&2
  exit 64
}

verify_digest() {
  local path="$1" actual
  actual="$(shasum -a 256 "$path" | awk '{print $1}')"
  [[ "$actual" == "$OTEL_JAVAAGENT_SHA256" ]] || {
    echo "OpenTelemetry Java Agent SHA-256 mismatch" >&2
    return 1
  }
}

if [[ -f "$DESTINATION" ]]; then
  verify_digest "$DESTINATION"
  chmod a-w "$DESTINATION"
  printf 'Pinned OpenTelemetry Java Agent already verified: %s\n' "$DESTINATION"
  exit 0
fi

TEMPORARY="$DESTINATION.partial.$$"
trap 'rm -f "$TEMPORARY"' EXIT
curl --fail --location --silent --show-error "$OTEL_JAVAAGENT_URL" --output "$TEMPORARY"
verify_digest "$TEMPORARY"
chmod 0444 "$TEMPORARY"
mv "$TEMPORARY" "$DESTINATION"
trap - EXIT
printf 'Pinned OpenTelemetry Java Agent downloaded and verified: %s\n' "$DESTINATION"
