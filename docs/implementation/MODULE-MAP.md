# Structural Module Map

This document describes ownership and dependency direction only. Current supported/incubating/planned/deferred status lives exclusively in [SCAFFOLD-PRODUCT-SURFACE.md](./SCAFFOLD-PRODUCT-SURFACE.md).

## Dependency direction

```text
foundation contracts
    ├── technical starters/adapters
    └── provider-owned service APIs
             ↓
business service implementations
             ↓
executable App Assemblies

testing may depend on public seams; production never depends on test support
```

App Assemblies are the only classpath composition roots. Business Services own reusable inbound adapter semantics; App Assemblies select and activate them with runtime capabilities and configuration. A multi-service App also assigns each service's `/api/{service}` External Route Prefix to its public Controllers, while leaving internal Controllers unchanged. A Compose file used by local acceptance does not turn the repository into a production deployment design.

## Platform artifacts

- `platform-dependencies`: dependency-management BOM; it installs nothing.
- `platform-kernel`: framework-light package seams for API values, errors, context, authorization, identifiers and transaction contracts.
- `platform-web-spring-boot-starter`: MVC/validation/correlation/Problem Details.
- `platform-auth-resource-server-autoconfigure`: JWT validation and trusted context entry.
- `platform-data-mybatis-plus-spring-boot-starter`: MyBatis-Plus tenant/data wiring and Spring transaction adapter.
- `platform-database-migration`: explicit component migration orchestration.
- `platform-message-core`: transport-neutral descriptor, Inbox and Outbox contracts/algorithms.
- `platform-messaging-kafka-spring-boot-starter`: JDBC stores, Kafka codecs/routes/transport, reliable consumer and relay.
- `platform-test-support`: test-only deterministic helpers.

Lock, scheduler and object-storage artifacts are independent capability experiments; see the product-surface page before consuming them.

## Business shape

```text
catalog-api       order-api       inventory-api
    ↓                 ↓                ↓
catalog-service   order-service   inventory-service
    ↓                 ↓                ↓
service-owned HTTP / MyBatis-Plus / messaging adapters
    ↓                 ↓                ↓
catalog-app       order-app       inventory-app
```

Each `*-api` owns protocol-neutral Commands, Queries and Views. Each `*-service` owns domain/application logic, domain-specific Repository interfaces and adapter implementations. Concrete inbound adapters remain package-separated configuration slices in the ordinary Service Jar until a second real transport implementation justifies another artifact. Tenant isolation is infrastructure-derived and fail closed; tenant parameters are not added to business interfaces merely to help persistence.

`order-service` synchronously consumes `catalog-api` through a concrete HTTP client selected by `order-app`. Inventory is asynchronous: Order commits an async command to its Outbox, Inventory atomically deduplicates/reserves/emits a result, and Order atomically deduplicates/applies the terminal state. There are no synchronous `order-client` or `inventory-client` artifacts without real consumers.

## App Assemblies

- `identity-app`: PostgreSQL opaque sessions, tenant memberships/permissions, gateway token exchange, audience-scoped service tokens and JWKS.
- `gateway-app`: edge routes and filters for login and versioned business endpoints; it owns no business Controller and does not expose internal Catalog/Identity/message routes. It strips the service-name prefix only when routing to a standalone service App and preserves it when routing to a multi-service App.
- `catalog-app`: internal JWT-protected product lookup and PostgreSQL persistence.
- `order-app`: public service API behind Gateway, Catalog HTTP client, Order persistence and Kafka result/Outbox assembly.
- `inventory-app`: Kafka-only business entry plus a health endpoint, Inventory persistence and result Outbox assembly.
- `app-monolith`: supported focused Catalog/Order/Inventory business-core composition under ADR 0022 and ADR 0027. It selects the Local `CatalogApi`, keeps Order/Inventory collaboration on the same Kafka Outbox/Inbox path, and prefixes each service's public Controllers according to ADR 0029. The separate Gateway therefore forwards the stable Order external path without stripping the service name; Gateway and Identity remain separate Apps in this topology.

The service-to-service security chain is USER JWT at Order, then an audience-scoped SERVICE JWT for Order→Catalog with the original initiator preserved. Catalog derives the tenant from the trusted `Tenant-Id` service header under the platform SERVICE-token rule.

## Verification topology

- Architecture tests enforce framework-free domain, dependency direction, production/test isolation, context ownership and no direct business `ThreadLocal`/executor coupling.
- PostgreSQL owns end-to-end, failure recovery and default runtime configuration.
- MySQL runs the same focused Repository, tenant, migration and durable-message persistence contracts without multiplying the full topology.
- The Notes consumer proves public artifacts independently. The topology-parameterized reference harness runs one public Gateway HTTP contract against both the five-App Golden Path and Gateway + Identity + business-core monolith, including Kafka outage/restart recovery. Focused Order, Inventory and Monolith App integration tests inject duplicate deliveries and prove Inbox idempotency without adding a test-only production route.

## Version governance

Spring Boot 4.1.1 manages the core stack, Spring Cloud BOM 2025.1.3 governs Cloud Stream 5.0.x, MyBatis-Plus is fixed at 3.5.17, and the reactor compiles/releases for JDK 26. Redisson and MinIO coordinates may be managed before their capabilities are promoted; that is version governance, not a support claim.
