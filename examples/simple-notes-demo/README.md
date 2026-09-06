# Simple Notes Demo

This is a deliberately small consumer project, not another platform module. It owns one `Note` aggregate and demonstrates how a normal service consumes the scaffold.

## What it uses

- its own Spring Boot 4.1.1 parent rather than `moduvera-parent`;
- `moduvera-bom` as an imported BOM;
- versionless dependencies on the Kernel and Message Core artifacts, Resource Server autoconfigure, and the Web, Data, Messaging and Migration Spring Boot starters;
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
