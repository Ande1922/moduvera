#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
APPS_FILE="$SCRIPT_DIR/apps.tsv"
DOCKERFILE="$PROJECT_ROOT/build/docker/Dockerfile.jvm"
IMAGE_REPOSITORY="${MODUVERA_IMAGE_REPOSITORY:-moduvera-local}"
IMAGE_TAG="${MODUVERA_IMAGE_TAG:-verification}"

REQUESTED_COUNT=$#
requested_apps=("$@")

selected() {
  local app="$1" requested
  [[ $REQUESTED_COUNT -eq 0 ]] && return 0
  for requested in "${requested_apps[@]}"; do
    [[ "$requested" == "$app" ]] && return 0
  done
  return 1
}

if [[ $REQUESTED_COUNT -gt 0 ]]; then
  for requested in "${requested_apps[@]}"; do
    awk -F '\t' -v app="$requested" '$1 == app { found = 1 } END { exit !found }' "$APPS_FILE" \
      || { echo "Unknown Runnable App: $requested" >&2; exit 64; }
  done
fi

find_app_jar() {
  local app="$1" candidate
  local candidates=()
  while IFS= read -r -d '' candidate; do
    case "$(basename "$candidate")" in
      *-sources.jar|*-javadoc.jar|*-tests.jar) continue ;;
    esac
    candidates+=("$candidate")
  done < <(find "$PROJECT_ROOT/apps/$app/target" -maxdepth 1 -type f \
    -name "$app-*.jar" -print0 2>/dev/null)
  if [[ ${#candidates[@]} -ne 1 ]]; then
    echo "Expected exactly one executable JAR for $app; run the App package build first" >&2
    return 1
  fi
  printf '%s\n' "${candidates[0]#$PROJECT_ROOT/}"
}

built=0
while IFS=$'\t' read -r app port; do
  [[ -n "$app" && "${app:0:1}" != "#" ]] || continue
  selected "$app" || continue
  jar="$(find_app_jar "$app")"
  image="$IMAGE_REPOSITORY/$app:$IMAGE_TAG"
  docker build \
    --file "$DOCKERFILE" \
    --build-arg "APP_JAR=$jar" \
    --build-arg "APP_PORT=$port" \
    --label "io.github.ande1922.moduvera.app=$app" \
    --tag "$image" \
    "$PROJECT_ROOT"
  echo "Built $image from $jar (EXPOSE $port)"
  built=$((built + 1))
done < "$APPS_FILE"

if [[ $built -eq 0 ]]; then
  echo "No Runnable App images were selected" >&2
  exit 64
fi
