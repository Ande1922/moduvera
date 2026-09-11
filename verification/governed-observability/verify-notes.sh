#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
out="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:?Choose a fresh absolute Notes evidence directory}"
[[ "$out" == /* && ! -e "$out/notes-agent.log" && ! -e "$out/receiver.port" && ! -e "$out/spans.jsonl" ]] || { echo 'Use a fresh absolute evidence directory' >&2; exit 64; }
mkdir -p "$out"
printf '%s\n' '{"notes_content":"notes-private-body-sentinel"}' > "$out/sentinels.json"
python3 "$SCRIPT_DIR/otlp_receiver.py" --port-file "$out/receiver.port" --output "$out/spans.jsonl" \
  --status "$out/receiver-status.json" --sentinels "$out/sentinels.json" > "$out/receiver.log" 2>&1 &
receiver=$!
trap 'kill "$receiver" 2>/dev/null || true; wait "$receiver" 2>/dev/null || true' EXIT
for i in $(seq 1 100); do
  [[ -s "$out/receiver.port" ]] && break
  kill -0 "$receiver" 2>/dev/null || { cat "$out/receiver.log"; exit 1; }
  sleep 0.05
done
[[ -s "$out/receiver.port" ]] || { echo 'Notes receiver failed to become ready' >&2; exit 1; }
export REFERENCE_OTLP_TRACES_ENDPOINT="http://127.0.0.1:$(cat "$out/receiver.port")/v1/traces"
bash "$SCRIPT_DIR/verify-notes-fixture.sh"
python3 "$SCRIPT_DIR/analyze_notes_fixture.py" "$out" > "$out/notes-analysis.json"
cat "$out/notes-analysis.json"
