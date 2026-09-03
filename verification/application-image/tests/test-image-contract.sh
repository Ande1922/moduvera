#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
IMAGE_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$IMAGE_DIR/../.." && pwd)"
DOCKERFILE="$PROJECT_ROOT/build/docker/Dockerfile.jvm"

fail() { echo "image contract test failed: $*" >&2; exit 1; }
require_text() { grep -Fq -- "$1" "$2" || fail "$2 is missing: $1"; }

[[ "$(find "$PROJECT_ROOT/build" -name 'Dockerfile*' -type f | wc -l | tr -d ' ')" == "1" ]] \
  || fail "expected exactly one repository-owned Dockerfile"
[[ -z "$(find "$PROJECT_ROOT/apps" -name 'Dockerfile*' -type f -print -quit)" ]] \
  || fail "Runnable Apps must not copy the shared Dockerfile"

require_text 'eclipse-temurin:26-jre@sha256:2b3c7b20375e9ac3ab6a7bc39357d3dbe2caf48378fe9e5c306a22da3499f170' "$DOCKERFILE"
require_text 'java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted' "$DOCKERFILE"
for layer in dependencies spring-boot-loader snapshot-dependencies application; do
  require_text "/workspace/extracted/$layer/ ./" "$DOCKERFILE"
done
require_text 'USER 10001:10001' "$DOCKERFILE"
require_text 'EXPOSE ${APP_PORT}' "$DOCKERFILE"
require_text 'ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]' "$DOCKERFILE"
grep -Eq '(^|[[:space:]])(mvn|mvnw|maven)([[:space:]]|$)' "$DOCKERFILE" \
  && fail "Dockerfile must consume a built JAR instead of running Maven"
grep -Eiq '(^|[[:space:]])ENV[[:space:]].*(SERVER_PORT|JDWP|JAVA_TOOL_OPTIONS)' "$DOCKERFILE" \
  && fail "Dockerfile must not bake server or debug configuration into the image"
grep -Eiq '^[[:space:]]*HEALTHCHECK' "$DOCKERFILE" \
  && fail "the shared image must not claim one universal health endpoint"

expected_apps="$(awk '!/^#/ && NF {print $1}' "$IMAGE_DIR/apps.tsv" | sort)"
actual_apps="$(find "$PROJECT_ROOT/apps" -mindepth 2 -maxdepth 2 -type f -name pom.xml \
  -exec dirname {} \; | xargs -n1 basename | sort)"
[[ "$expected_apps" == "$actual_apps" ]] || fail "apps.tsv does not enumerate every Runnable App"

require_text '**' "$PROJECT_ROOT/.dockerignore"
require_text '!apps/*/target/*.jar' "$PROJECT_ROOT/.dockerignore"
for script in "$IMAGE_DIR"/*.sh "$IMAGE_DIR"/tests/*.sh; do bash -n "$script"; done
python3 -c 'import ast, pathlib, sys; ast.parse(pathlib.Path(sys.argv[1]).read_text())' \
  "$IMAGE_DIR/inspect_images.py"

echo "Application image static contract: PASS"
