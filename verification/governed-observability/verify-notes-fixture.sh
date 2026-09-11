#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/agent-runtime.sh"
EVIDENCE_DIR="${MODUVERA_OBSERVABILITY_EVIDENCE_DIR:?Notes evidence directory is required}"
[[ -d "$EVIDENCE_DIR" ]] || exit 64
AGENT="${MODUVERA_OTEL_JAVAAGENT:-}"
EXTENSION="${MODUVERA_OTEL_AGENT_EXTENSION:-}"
governed_agent_preflight "$AGENT" "$EXTENSION" > "$EVIDENCE_DIR/notes-agent-preflight.log"
OPTIONS="$(governed_agent_java_options simple-notes-demo "$AGENT" "$EXTENSION") -Dmoduvera.test.agent=true -Dnotes.jacoco.sha256=$JACOCO_AGENT_SHA256"
OPTIONS+=" -Dotel.exporter.otlp.metrics.endpoint=${REFERENCE_OTLP_TRACES_ENDPOINT%/v1/traces}/v1/metrics -Dotel.metric.export.interval=500"
cd "$PROJECT_ROOT"
# Install this checkout's public artifacts before consuming Notes with its own Parent.
run() {
  local label="$1"; shift
  set +e
  "$@" > "$EVIDENCE_DIR/$label.log" 2>&1
  local result=$?
  set -e
  printf '%s\n' "$result" > "$EVIDENCE_DIR/$label.exit"
  [[ "$result" == 0 ]] || { tail -40 "$EVIDENCE_DIR/$label.log"; exit "$result"; }
}
run notes-bom-install ./mvnw -B -ntp -f framework/bom/pom.xml install
run notes-framework-install ./mvnw -B -ntp -pl examples/simple-notes-demo -am install -DskipTests -DskipITs
run notes-effective-pom ./mvnw -B -ntp -f examples/simple-notes-demo/pom.xml help:effective-pom "-Doutput=$EVIDENCE_DIR/notes-effective-pom.xml"
run notes-dependencies ./mvnw -B -ntp -f examples/simple-notes-demo/pom.xml dependency:tree
run notes-classpath ./mvnw -B -ntp -f examples/simple-notes-demo/pom.xml dependency:build-classpath -DincludeScope=runtime "-Dmdep.outputFile=$EVIDENCE_DIR/notes-runtime-classpath.txt"
run notes-local-repository ./mvnw -B -ntp -f examples/simple-notes-demo/pom.xml help:evaluate -Dexpression=settings.localRepository "-Doutput=$EVIDENCE_DIR/notes-local-repository.txt"
JACOCO_AGENT="$(cat "$EVIDENCE_DIR/notes-local-repository.txt")/org/jacoco/org.jacoco.agent/$JACOCO_AGENT_VERSION/org.jacoco.agent-$JACOCO_AGENT_VERSION-runtime.jar"
governed_agent_preflight "$AGENT" "$EXTENSION" "$JACOCO_AGENT" > "$EVIDENCE_DIR/notes-agent-preflight.log"
run notes-agent ./mvnw -B -ntp -f examples/simple-notes-demo/pom.xml test-compile failsafe:integration-test failsafe:verify -Dit.test=NotesObservabilityIT "-Dnotes.agent.options=$OPTIONS" "-Djacoco.destFile=$EVIDENCE_DIR/notes-jacoco.exec" -Djacoco.append=false
[[ -s "$EVIDENCE_DIR/notes-jacoco.exec" ]] || { echo 'Current Notes JVM did not produce JaCoCo execution data' >&2; exit 1; }
run notes-coverage-report ./mvnw -B -ntp -f examples/simple-notes-demo/pom.xml jacoco:report "-Djacoco.dataFile=$EVIDENCE_DIR/notes-jacoco.exec"
cp examples/simple-notes-demo/target/site/jacoco/jacoco.xml "$EVIDENCE_DIR/notes-jacoco.xml"
rg -q 'Governed OpenTelemetry Agent extension handshake: PASS' "$EVIDENCE_DIR/notes-agent.log"
python3 "$SCRIPT_DIR/verify-minimal-consumers.py" "$EVIDENCE_DIR"
echo 'Independent Notes locked-Agent fixture: PASS; run analyze_notes_fixture.py against receiver output.'
