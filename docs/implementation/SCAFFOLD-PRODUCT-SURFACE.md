# Current Scaffold Product Surface

This is the only current status source for the repository. Status is based on executable consumer evidence, not the presence of a POM, class, version property or BOM coordinate. Last verified: 2026-09-01.

## Supported

| Artifact/capability | Supported behavior | Evidence boundary |
|---|---|---|
| `platform-dependencies` | Version management for platform and governed third-party coordinates | versionless BOM smoke and independent-parent Notes consumer |
| `platform-kernel` | coded errors, execution/tenant/actor/initiator context, authorization, identifiers, paging and `TransactionBoundary`, separated by package | framework-free tests plus architecture rules; platform/virtual-thread snapshot propagation tests |
| `platform-web-spring-boot-starter` | native success bodies, validation, correlation and RFC 9457 errors | starter contract tests, Notes HTTP and dual-topology public black box |
| `platform-auth-resource-server-autoconfigure` | signed JWT issuer/audience checks, USER/SERVICE tenant rules, fail-closed context lifecycle and 401/403 responses | signed-token App tests, virtual-thread HTTP and public Gateway flow |
| `platform-data-mybatis-plus-spring-boot-starter` | MyBatis-Plus 3.5.17, context-derived tenant enforcement and one READ_COMMITTED top-level transaction boundary | PostgreSQL consumer Apps/Notes and real MySQL repository TCK |
| `platform-database-migration` | component identity, private history, guarded baseline/migrate/validate | PostgreSQL/MySQL migration tests and all persistent Apps |
| `platform-message-core` | transport-neutral Event/Async Command descriptors, durable Outbox state machine and atomic Inbox contract | unit contracts plus PostgreSQL/MySQL store integration |
| `platform-messaging-kafka-spring-boot-starter` | Cloud Stream imperative model, structured CloudEvents for Events, distinct async-command envelope, logical route mapping, synchronous broker ACK, JDBC Outbox/Inbox, bounded retry and Kafka DLQ integration | real Kafka Notes/App tests, topology-specific duplicate-delivery ITs and dual-topology recovery black box |
| `platform-test-support` | deterministic clocks, bounded eventually and run identifiers | test-scope consumers only |

The supported database default is PostgreSQL. MySQL is supported as a compatibility target for Catalog, Order, Inventory and messaging persistence contracts; it does not own Gateway/Kafka end-to-end, AI/vector, performance or failure-recovery claims.

## Consumer-verified reference products

- `examples/simple-notes-demo`: small independent-parent consumer showing direct BOM/Starter use with HTTP, JWT, PostgreSQL, Kafka and DLQ.
- `gateway-app`, `identity-app`, `catalog-app`, `order-app`, `inventory-app`: a separate-process reference product using opaque sessions, internal JWTs, real HTTP, durable messaging and asynchronous order fulfillment.
- `gateway-app`, `identity-app`, `app-monolith`: a focused business-core modular-monolith topology reusing the same service-owned HTTP/message adapters, Local `CatalogApi`, and Kafka Outbox/Inbox flow.
- `scripts/reference-product/verify.sh`: topology-parameterized local acceptance harness that runs one public HTTP contract against both supported topologies, including concurrent tenant isolation and Kafka/App stop-recovery. Its Compose assets are not production deployment guidance.

## Incubating

- `platform-lock-core`, `platform-lock-local` and `platform-scheduler-spring-boot-starter`: tested local behavior, but no distributed lock adapter and no reference-product consumer.
- `platform-object-storage-api`: contract only; no S3 adapter or business consumer.
- `platform-architecture-testkit`: repository-internal gate; not yet an external testing product.

## Planned

- governed observability and HTTP-client Starters;
- cache and Redisson distributed-lock adapters;
- S3-compatible object-storage adapter;
- PostgreSQL-native AI/vector capability qualification when a real Agent/RAG use case exists;
- production identity signing-key storage, rotation and multi-instance operation.

## Deferred

- jOOQ and equal multi-ORM support;
- RabbitMQ, RocketMQ or equal multi-broker support;
- generic Saga/workflow/distributed-transaction runtime;
- automatic multi-level cache, sharding, read/write splitting, XA and RLS-by-default;
- Kubernetes/Helm/release or production deployment templates.

## Promotion rule

A candidate becomes supported only when a standalone consumer uses its public surface and passes the relevant real-infrastructure contract. The second real implementation or consumer—not a hypothetical future choice—justifies a new reusable seam or Maven artifact. Internal organization defaults to packages.
