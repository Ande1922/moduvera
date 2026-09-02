# Grill Closure and V0.1 Validation Backlog

## Grill state

The broad architecture Grill is closed. The refreshed original conversation advances through Q424, after which the user stopped speculative expansion and asked to implement. The user subsequently clarified on 2026-08-30 that the target is the complete scaffold except deployment, and that deployment itself remains unconfirmed.

No unanswered recommendation is silently promoted to an accepted product decision. Weakly confirmed details are carried as implementation hypotheses and must be proven or revised by the first vertical slice.

## Current execution boundary

Implementation proceeds in evidence-producing vertical increments. The first required delivery path is a multi-process microservice reference system:

- separate Catalog, Order and Inventory App Assemblies as the Golden Path; a Local modular-monolith assembly is a required second topology with focused acceptance under ADR 0022;
- Catalog, Order, and Inventory business boundaries;
- Order synchronously reads Catalog, persists `PENDING_STOCK`, and writes a reserve-inventory command to Outbox;
- Inventory consumes idempotently and publishes reserved or rejected results;
- Order consumes the result and changes state;
- Tenant Context and use-case authorization fail closed;
- PostgreSQL and MyBatis-Plus implement the default persistence path; MySQL earns compatibility status through the same focused Repository and migration contracts;
- Spring Cloud Stream imperative functions with the Kafka Binder implement messaging over transactional Outbox/Inbox; no second Broker is required;
- remote HTTP and Kafka boundaries are exercised by the Golden Path, while protocol-neutral Service APIs and Local implementations must complete the modular-monolith flow without creating a second full infrastructure matrix;
- the same black-box pytest suite can target any externally supplied environment without owning its deployment.

## Implementation hypotheses to validate

These are not a new Grill frontier. The V0.1 Demo should make them concrete, retain evidence, and update the ledger rather than asking speculative questions up front.

- Exact separate-process and modular-monolith App Assembly layouts, including the classpath guarantee that exactly one required Service API implementation is present in each topology.
- Tenant-transparent business Repository behavior and fail-closed infrastructure enforcement on PostgreSQL/MySQL; the API shape is decided, while the MyBatis-Plus implementation and TCK evidence remain to be built.
- Domain/data common-field mapping and the `AggregateVersion` representation; the concrete proposal is isolated in [DATA-MODEL-CONFIRMATION.md](../implementation/DATA-MODEL-CONFIRMATION.md) pending one overall decision.
- MyBatis-Plus behavior across PostgreSQL and MySQL under one Repository TCK, with PostgreSQL as the Golden Path.
- Service-owned PostgreSQL/MySQL migration scripts and MyBatis-Plus persistence mappings.
- Spring Boot Client Group behavior with the shared Apache HC5 connection manager.
- API versioning, OpenAPI grouping, and contract snapshots.
- Numeric quality thresholds such as changed-line coverage and CRAP after the Demo establishes a real baseline.
- Spring Cloud Stream/Kafka Binder behavior on JDK 26, plus optional targeted PIT. Spring Boot, compiler warnings, Spotless, PMD, JaCoCo, ArchUnit, Flyway and Testcontainers now have local JDK 26 build evidence.

## Explicitly deferred

- Generic request idempotency and result replay beyond business-specific idempotency.
- jOOQ and an equal multi-ORM matrix.
- RabbitMQ, RocketMQ and an equal multi-broker matrix.
- A Cartesian product of Local/HTTP topology, database, Broker and failure-recovery matrices; ADR 0022 requires focused modular-monolith acceptance instead.
- XA/Seata, Process Manager runtime, workflow DSL, compensation engine, and reliable workflow timers.
- Cross-service remaining-deadline propagation.
- PostgreSQL RLS as a default rather than optional defense in depth.
- Read/write splitting, tenant-level database routing, and sharding middleware.
- Distributed Caffeine invalidation and automatic two-level caching.
- Device simulators in the scaffold; protocol-specific simulators belong to later business integration work.
- Image thumbnails, compression, cropping, and format conversion.
- Cosign configuration and deployment-time image signature verification.
- Dockerfiles, image publication, production/single-host Compose guidance, Kubernetes, Helm, deployment pipelines, and rolling-release settings until the deployment design is confirmed. The implemented Compose file is a disposable local acceptance dependency harness only.
- Mandatory mutation-test gates; PIT starts as an optional targeted profile.
- Advanced search such as pinyin, full text, similarity, and specialized indexes.

## Accepted delivery constraints

- Surefire runs unit tests and Failsafe runs integration tests; pytest is a separate `uv` project and is not hidden inside Maven.
- Real dependencies use Testcontainers, with containers shared within a test module and recreated for each CI build.
- Asynchronous assertions use bounded `eventually` polling, never fixed sleeps.
- Flaky tests do not pass through automatic reruns; temporary quarantine requires an owner, reason, and expiry.
- Spotless is the formatting entry point; static-analysis rules must be few, compatible, and non-overlapping.
- Builds pin Maven/plugins/dependencies, reject dynamic external versions, generate SBOMs, and scan the produced software inventory when tooling is available.
- Database migrations remain service-owned Flyway artifacts. Who runs them, in what order, and through which deployment mechanism remains a deployment decision. Q423 was withdrawn because it duplicated the already accepted Forward-Fix/Expand-Contract migration semantics.

## Deployment questions left open

- Broader developer-environment mechanism beyond the accepted disposable Compose + host-process reference harness.
- Production runtime and packaging boundary: Kubernetes/Helm, another orchestrator, or a simpler host model.
- Ownership and lifecycle of stateful middleware.
- Migration orchestration and release ordering.
- Health/readiness, graceful replacement, rollback, image publication, signing, verification, and supply-chain enforcement at deployment time.
