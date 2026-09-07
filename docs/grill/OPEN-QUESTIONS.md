# Grill Closure and V0.1 Validation Backlog

Reading note (2026-09-07): this file preserves the Grill closure and V0.1
planning backlog. Topology wording is reconciled with
[ADR 0038](../adr/0038-retain-monolith-as-on-demand-assembly.md). Other backlog
entries are historical, not proof of current unfinished work; resolve current
scope from the active request/spec and applicable ADRs, and capability status
from the [Product Surface](../implementation/SCAFFOLD-PRODUCT-SURFACE.md).

## Grill state

The broad architecture Grill is closed. The refreshed original conversation advances through Q424, after which the user stopped speculative expansion and asked to implement. The user subsequently clarified on 2026-08-30 that the target is the complete scaffold except deployment, and that deployment itself remains unconfirmed.

No unanswered recommendation is silently promoted to an accepted product decision. Weakly confirmed details are carried as implementation hypotheses and must be proven or revised by the first vertical slice.

## Reference delivery boundary

Implementation proceeds in evidence-producing vertical increments. The first required delivery path is a multi-process microservice reference system:

- separate Catalog, Order and Inventory App Assemblies as the only default Golden Path; retain the Local modular-monolith assembly with basic compilation and shared architecture checks, while runtime qualification is opt-in under [ADR 0038](../adr/0038-retain-monolith-as-on-demand-assembly.md);
- Catalog, Order, and Inventory business boundaries;
- Order synchronously reads Catalog, persists `PENDING_STOCK`, and writes a reserve-inventory command to Outbox;
- Inventory consumes idempotently and publishes reserved or rejected results;
- Order consumes the result and changes state;
- Tenant Context and use-case authorization fail closed;
- PostgreSQL and MyBatis-Plus implement the default persistence path; MySQL earns compatibility status through the same focused Repository and migration contracts;
- Spring Cloud Stream imperative functions with the Kafka Binder implement messaging over transactional Outbox/Inbox; no second Broker is required;
- remote HTTP and Kafka boundaries are exercised by the Golden Path; retain protocol-neutral Service APIs and Local collaboration, qualifying the monolith flow when runtime support is explicitly in scope;
- the same black-box pytest suite can target any externally supplied environment without owning its deployment.

## Historical implementation hypotheses

These were V0.1 validation questions, not a new Grill frontier. Check current implementation and ticket evidence before treating any item as remaining work.

- Exact App Assembly layouts and the classpath guarantee that exactly one required Service API implementation is present; qualify the retained modular-monolith runtime only when explicitly in scope under ADR 0038.
- Tenant-transparent business Repository behavior and fail-closed infrastructure enforcement on PostgreSQL/MySQL; the API shape is decided, while the MyBatis-Plus implementation and TCK evidence remain to be built.
- Whether the current primitive aggregate version should become an explicit `AggregateVersion` value object after a real consumer demonstrates enough benefit. Persistence records already declare tenant, audit and version fields explicitly, and no shared `BaseEntity`, universal Mapper or Repository is planned.
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
- A Cartesian product of Local/HTTP topology, database, Broker and failure-recovery matrices; [ADR 0038](../adr/0038-retain-monolith-as-on-demand-assembly.md) makes monolith runtime qualification an explicit opt-in rather than an ordinary iteration gate.
- XA/Seata, Process Manager runtime, workflow DSL, compensation engine, and reliable workflow timers.
- Cross-service remaining-deadline propagation.
- PostgreSQL RLS as a default rather than optional defense in depth.
- Read/write splitting, tenant-level database routing, and sharding middleware.
- Distributed Caffeine invalidation and automatic two-level caching.
- Device simulators in the scaffold; protocol-specific simulators belong to later business integration work.
- Image thumbnails, compression, cropping, and format conversion.
- Cosign configuration and deployment-time image signature verification.
- Image publication, production/single-host Compose guidance, Kubernetes, Helm, deployment pipelines, and rolling-release settings until the deployment design is confirmed. The shared Runnable App Dockerfile is only an image-construction baseline, and the implemented Compose file remains a disposable local acceptance dependency harness.
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
