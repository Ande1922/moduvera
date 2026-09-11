# Simple Notes Demo

This is a deliberately small consumer project, not another platform module. It owns one `Note` aggregate and demonstrates how a normal service consumes the scaffold.

## What it uses

- its own Spring Boot 4.1.1 parent rather than `moduvera-parent`;
- `moduvera-bom` as an imported BOM;
- versionless dependencies on the Kernel and Message Core artifacts, Resource Server autoconfigure, and the Logging, Web, Data, Messaging and Migration Spring Boot starters;
- the typed `ApplicationMessageHandler<P>` seam, with a fixed `notes-audit` Inbox identity and the original `MessageId` supplied by the standard Messaging `Consumer`;
- native HTTP responses and RFC 9457 errors;
- JWT-derived `ExecutionContext`, use-case permission checks and transparent tenant isolation;
- explicit JDK executor submission capture and registration-time callback binding;
- PostgreSQL, MyBatis-Plus, `TransactionBoundary` and component-owned Flyway SQL.

It does not import Catalog, Order, Inventory or `app-monolith`. It exercises Kafka and JDBC Outbox/Inbox behavior as consumer evidence for the documented Messaging capability; it does not establish any additional platform support beyond the [current capability status](../../docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md), and it does not claim MySQL support.

## Read the usage path

The useful path is intentionally short:

1. [`pom.xml`](./pom.xml) shows the consumer dependency surface.
2. [`NoteController.java`](./src/main/java/io/github/ande1922/moduvera/example/notes/interfaces/http/NoteController.java) is ordinary native HTTP code.
3. [`NoteApplicationService.java`](./src/main/java/io/github/ande1922/moduvera/example/notes/application/NoteApplicationService.java) uses permissions and `TransactionBoundary` without tenant parameters.
4. [`MybatisPlusNoteRepository.java`](./src/main/java/io/github/ande1922/moduvera/example/notes/infrastructure/persistence/MybatisPlusNoteRepository.java) derives tenant identity from the trusted context at the infrastructure boundary.
5. [`NoteCreatedHandler.java`](./src/main/java/io/github/ande1922/moduvera/example/notes/application/NoteCreatedHandler.java) performs the committed Inbox precheck and records a new receipt inside one atomic Inbox transaction.
6. [`NotesDemoIT.java`](./src/test/java/io/github/ande1922/moduvera/example/notes/NotesDemoIT.java) drives the result through real HTTP, PostgreSQL and Kafka, and composes explicit trusted Holder callers with direct tasks and externally completed callbacks before business reads.

The shared capture, lifecycle and unsupported-boundary rules are in the
[Execution Context propagation guide](../../docs/implementation/EXECUTION-CONTEXT-PROPAGATION.md).

## Automated proof

From the repository root with JDK 26 and Docker/OrbStack available:

```bash
DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock \
  ./mvnw -pl examples/simple-notes-demo -am verify
```

To verify the independent Spring Boot parent, imported BOM and versionless public dependencies against artifacts produced from the current checkout, install them and then invoke the Notes POM as a standalone Maven project:

```bash
./mvnw -DskipITs install
./mvnw -f examples/simple-notes-demo/pom.xml verify
```

The test proves:

- an authenticated writer creates a note and receives an unwrapped `201` body;
- the same tenant reads it, while another tenant receives `404`;
- missing permission uses the Web Starter's `403 security.permission-denied` problem, while missing authentication returns `401`;
- invalid input returns `400 application/problem+json` with the correlation ID;
- a directly submitted task and a callback completed later on an external worker both retain their complete request identity while reading real PostgreSQL business state, then restore that worker exactly;
- Platform, absent and cross-tenant async reads fail closed without changing Note, Outbox or receipt state;
- the Notes and Messaging migration definitions have distinct component identities and Flyway histories;
- Kafka publication and standard-Consumer delivery to the typed Application Handler, JDBC Outbox/Inbox, committed-replay skipping, retry and DLQ behavior remain active;
- PostgreSQL migration, Mapper registration, TenantLine and `TransactionBoundary` are all active.

## Governed observability proof

Ordinary `verify` runs the consumer without an OpenTelemetry SDK. It proves application and
infrastructure behavior, but does not establish exported Trace causality. The explicit
versionless Logging starter supplies ECS JSON stdout and trusted context projection. The
application opts `notes.events` into the Relay business boundary; the test uses the same
configured Worker, Observer, Lifecycle and Store with background Relay disabled so it can
control ACK/writeback and recovery windows.

With JDK 26 and Docker available, prepare the fixed external Agent and extension from the
repository root. The Agent 2.31.1 / API and SDK 1.65.0 combination and artifact SHA-256 values
are pinned in [agent.lock](../../verification/governed-observability/agent.lock); launcher
preflight rejects a missing or mismatched artifact. This does not add an SDK or tracing
bridge to the application's dependencies.

```bash
bash verification/governed-observability/fetch-agent.sh /tmp/notes-otel-agent.jar
./mvnw -f verification/governed-observability/agent-extension/pom.xml package
export MODUVERA_OTEL_JAVAAGENT=/tmp/notes-otel-agent.jar
export MODUVERA_OTEL_AGENT_EXTENSION="$PWD/verification/governed-observability/agent-extension/target/moduvera-governed-otel-agent-extension-0.1.0-SNAPSHOT.jar"
export MODUVERA_OBSERVABILITY_EVIDENCE_DIR="$(mktemp -d /tmp/notes-observability.XXXXXX)"
bash verification/governed-observability/verify-notes.sh
```

The [launcher](../../verification/governed-observability/verify-notes.sh) starts a local
HTTP/protobuf OTLP receiver, installs this checkout's public framework artifacts and BOM,
then invokes the Notes POM independently. It retains effective POM, runtime classpath,
build/test exits, actual stdout, safe HTTP/Kafka receipts and OTLP spans. Three separate
minimal projects also compile and start with only Kernel, Message Core or Logging as their
public dependency; their runtime classpaths exclude Servlet, Reactor, Kafka and OTel SDK.

[`NotesObservabilityIT`](./src/test/java/io/github/ande1922/moduvera/example/notes/NotesObservabilityIT.java)
and the [evidence analyzer](../../verification/governed-observability/analyze_notes_fixture.py) cover:

- Real HTTP create/read, permission/validation/pre-authentication errors and tenant isolation;
  server-generated Correlation IDs, `X-Trace-Id`, stdout and server Span identity; an upstream
  unsampled context keeps correlation while producing no exported Span.
- PostgreSQL Durable append, actual Kafka ACK followed by a rejected PostgreSQL publication
  update, retry to terminal, production redrive into a new publication generation, then
  actual redelivery. The original creation context, Correlation ID and immutable message
  fields survive recovery. The committed Inbox precheck skips the replay; consumption is
  confirmed by a shared progress barrier captured before sending and actual group offsets.
- A separate Immediate send through the public capability reaches the real consumer and
  creates no Outbox row. Wire creation context and Agent transport context remain distinct;
  `mq.process` follows the native transport parent and links to creation.
- All nine existing Outbox Micrometer families update through Worker/Maintenance callbacks,
  including real row-lock contention, stale claim writeback, cleanup and backlog. The
  `broker.ack` timer retains the Observer's send-plus-writeback duration semantics.
  Labels remain bounded. No public metrics endpoint is added.

The Agent uses `tracecontext`, `parentbased_always_on`, OTLP `http/protobuf` traces,
`otel.logs.exporter=none` and `otel.metrics.exporter=none`. Logs remain ECS stdout and
metrics remain in the application Micrometer registry. The test configures a potential
metrics endpoint and 500 ms period, confirms positive Trace reception, and keeps the JVM
alive for at least two seconds while observing zero SDK metrics/log requests. This is a
bounded fixture observation, not a production monitoring guarantee. The
[shared launch policy](../../verification/governed-observability/agent-runtime.sh) supplies
all Agent options and the required runtime handshake. The Notes Failsafe configuration
combines those options with late-resolved JaCoCo arguments. The same JVM verifies the
locked JaCoCo digest, and the run retains fresh execution data plus a report showing
covered Notes application/controller lines. Ordinary tests keep their existing JaCoCo
instrumentation.

Upgrade existing supported Messaging consumers before publishers so readers understand
the creation/transport separation. Apply the appended Messaging schema migrations under
the explicit component migration policy before starting code that requires them; retain
normal schema validation on later starts. This does not alter the old disposable Notes
database policy below. Full CAS, multiple-redrive and process-loss matrices belong to the
[framework qualification fixtures](../../verification/governed-observability/README.md);
this Notes scenario proves the independent assembly on PostgreSQL/Kafka only.
Job, device, AI inference and long-connection adapters are not added or qualified here.

## Manual run

Start PostgreSQL:

```bash
docker run --rm --name simple-notes-postgres \
  -e POSTGRES_DB=notes_demo \
  -e POSTGRES_USER=notes \
  -e POSTGRES_PASSWORD=notes \
  -p 5432:5432 postgres:18.6
```

The migration ownership transition to separate `messaging` and `notes_demo` histories does not support upgrading a database created by an earlier Notes demo revision. Those databases are disposable fixtures; do not rewrite their Flyway history or add a compatibility migration. Stop the demo, remove the old no-volume container, and recreate it with the command above:

```bash
docker rm -f simple-notes-postgres
```

The replacement `docker run --rm` container starts from an empty database and is removed again when stopped.

A reachable Kafka broker is also required for the configured `noteCreated` binding and
Outbox publication. The integration tests provision Kafka from
`confluentinc/cp-kafka:7.3.3`; a manual run must supply its reachable bootstrap address:

```bash
export SPRING_CLOUD_STREAM_KAFKA_BINDER_BROKERS=localhost:9092
```

Install the scaffold artifacts once, then start the independent project:

```bash
./mvnw -DskipITs install
cd examples/simple-notes-demo
```

The normal application configuration validates existing schema and history without writing to the database. For the fresh disposable database above, initialize it explicitly on the first run:

```bash
../../mvnw spring-boot:run \
  -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--moduvera.database.migration.mode=startup --moduvera.database.migration.initialize=true"
```

Subsequent runs can use the normal validation policy:

```bash
../../mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Create and read a note:

```bash
curl -i http://localhost:8080/api/v1/notes \
  -H 'Authorization: Bearer tenant-a-writer' \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: manual-1' \
  -d '{"content":"hello scaffold"}'

curl -i http://localhost:8080/api/v1/notes/REPLACE_WITH_ID \
  -H 'Authorization: Bearer tenant-a-reader'
```

The three accepted demo tokens are `tenant-a-writer`, `tenant-a-reader` and `tenant-b-writer`. [`DemoJwtDecoderConfiguration.java`](./src/main/java/io/github/ande1922/moduvera/example/notes/config/DemoJwtDecoderConfiguration.java) is intentionally profile-scoped and insecure; replace it with normal issuer/JWK configuration outside this demo.
