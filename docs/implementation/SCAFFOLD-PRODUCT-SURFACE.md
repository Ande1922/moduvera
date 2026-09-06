# Current Scaffold Product Surface

This is the only current status source for the repository. Status is based on executable consumer evidence, not the presence of a POM, class, version property or BOM coordinate. Last verified: 2026-09-06.

## Supported

| Artifact/capability | Supported behavior | Evidence boundary |
|---|---|---|
| `moduvera-bom` | Version management for framework and governed third-party coordinates | versionless BOM smoke and independent-parent Notes consumer |
| `moduvera-kernel` | coded errors, Platform/Tenant execution context, restorable present/absent snapshots, request-bound callbacks, selected JDK executor propagation, authorization, identifiers, paging and `TransactionBoundary`, separated by package | framework-free tests and architecture rules; independent Notes consumer direct-task and externally completed callback compositions with real PostgreSQL business reads; `java.base`-only artifact check |
| `moduvera-web-spring-boot-starter` | native success bodies, validation, correlation and RFC 9457 errors | starter contract tests, Notes HTTP and dual-topology public black box |
| `moduvera-auth-resource-server-autoconfigure` | signed JWT issuer/audience checks, USER/SERVICE tenant rules, selected Servlet Platform/Tenant boundaries, fail-closed context lifecycle and 401/403 responses | signed-token App tests, real and virtual request threads, dispatch cleanup, Catalog/Notes consumers and public Gateway flow |
| `moduvera-spring-task-context` | per-submission present/absent propagation for explicitly selected Spring `TaskExecutor` and `@Async` routes, with native Future and executor lifecycle | independent Spring Boot-parent consumer using a real `ApplicationContext`, selected and unselected executors, actual Async proxies and Spring Framework 7.0.9 |
| `moduvera-reactor-context` | explicit trusted/captured native Reactor Context plus local synchronous callback restoration; no global Hook | independent Reactor-only consumer using Reactor Core 3.8.7 across a real scheduler, plus adapter retry/cancel/absence tests |
| `moduvera-spring-ai-context` | per-request native Advisor/Tool context, stateless ToolCallback restoration and retained-response mapping | independent consumer using Spring AI 2.0.1 `ChatClient`, two real `ToolCallingAdvisor` tool rounds and final streaming response with a test-source scripted model; no external provider claim |
| `moduvera-data-mybatis-plus-spring-boot-starter` | MyBatis-Plus 3.5.17, context-derived tenant enforcement and one READ_COMMITTED top-level transaction boundary | PostgreSQL consumer Apps/Notes and real MySQL repository TCK |
| `moduvera-database-migration` | component identity, private history, guarded baseline/migrate/validate | PostgreSQL/MySQL migration tests and all persistent Apps |
| `moduvera-message-core` | transport-neutral Event/Async Command descriptors, durable Outbox state machine and atomic Inbox contract | unit contracts plus PostgreSQL/MySQL store integration |
| `moduvera-messaging-kafka-spring-boot-starter` | Cloud Stream imperative model, structured CloudEvents for Events, distinct tenant-only async-command envelope, logical route mapping, handler-bound context reconstruction and reliable inbound endpoints, synchronous broker ACK, JDBC Outbox/Inbox, bounded retry and Kafka DLQ integration | real Kafka Notes/App tests, provider-contract TCK, same-partition negative barriers, topology-specific duplicate-delivery ITs and dual-topology recovery black box; plaintext fixtures do not verify producer authentication or destination ACLs |
| `moduvera-test-support` | deterministic clocks, bounded eventually and run identifiers | test-scope consumers only |
| Runnable App image construction | all six `apps/` assemblies use one parameterized Dockerfile over Maven-built JARs, with Boot tools layers, a digest-pinned Java 26 JRE, root-owned read-only content, non-root execution and runtime-owned configuration | all-image build/inspection plus `catalog-app` health and HTTP smoke against real PostgreSQL and a live `app-monolith` default-port probe |

The supported database default is PostgreSQL. MySQL is supported as a compatibility target for Catalog, Order, Inventory and messaging persistence contracts; it does not own Gateway/Kafka end-to-end, AI/vector, performance or failure-recovery claims.

## Consumer-verified reference products

- `examples/simple-notes-demo`: small independent-parent consumer showing direct BOM/Starter use with HTTP, JWT, PostgreSQL, Kafka and DLQ.
- `verification/moduvera-spring-task-context-consumer`: independent-parent selected Spring task and actual `@Async` consumer.
- `verification/moduvera-reactor-context-consumer`: independent-parent Reactor-only consumer with native subscription-context propagation.
- `verification/moduvera-spring-ai-context-consumer`: independent-parent Spring AI consumer with request, two-round tool and final streaming-response context.
- `gateway-app`, `identity-app`, `catalog-app`, `order-app`, `inventory-app`: a separate-process reference product using opaque sessions, internal JWTs, named Apache HC5 Catalog/Identity HTTP Service Client Groups, durable messaging and asynchronous order fulfillment.
- `gateway-app`, `identity-app`, `app-monolith`: a focused business-core modular-monolith topology reusing the same service-owned HTTP/message adapters, Local `CatalogApi`, and Kafka Outbox/Inbox flow.
- `verification/reference-product/harness/verify.sh`: topology-parameterized local acceptance harness that runs one public HTTP contract against both supported topologies, including concurrent tenant isolation and Kafka/App stop-recovery. Its Compose assets are not production deployment guidance.

The Catalog and Identity HTTP Service Client Groups remain private to Order. No framework HTTP-client Starter is introduced before another independent consumer establishes a reusable seam.

## Incubating / Frozen

- `moduvera-lock-core`, `moduvera-lock-local` and `moduvera-scheduler-spring-boot-starter`: tested local behavior, but no reference-product consumer. Their public surfaces are frozen until a real business use case supplies requirements.
- `moduvera-architecture-testkit`: repository-internal gate; not yet an external testing product.

## Planned

- governed observability;
- WebFlux request-entry integration and complete MVC async-return integration beyond the supported explicit Reactor and task/callback templates;
- repository-owned business-service onboarding recipe and AI entry point, without freezing the evolving service shape into a code generator;
- PostgreSQL-native AI/vector capability qualification when a real Agent/RAG use case exists;

## Deferred

- jOOQ and equal multi-ORM support;
- RabbitMQ, RocketMQ or equal multi-broker support;
- generic Saga/workflow/distributed-transaction runtime;
- reusable cache policy until a real business consumer establishes its contract;
- automatic multi-level cache, sharding, read/write splitting, XA and RLS-by-default;
- Kubernetes/Helm/release or production deployment templates.

The reference `identity-app` intentionally uses ephemeral signing keys as Demo evidence. Production IAM key storage, rotation and multi-instance operation are outside the current scaffold product boundary.

## Withdrawn

- Object Storage: the previous speculative API and object/upload model have been removed. A future consumer must redefine its storage boundary from concrete business semantics.

## Promotion rule

A candidate becomes supported only when a standalone consumer uses its public surface and passes the relevant real-infrastructure contract. The second real implementation or consumer—not a hypothetical future choice—justifies a new reusable seam or Maven artifact. Internal organization defaults to packages.
