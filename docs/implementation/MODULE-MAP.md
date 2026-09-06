# Structural Module Map

This document describes ownership and dependency direction only. Current supported/incubating/planned/deferred status lives exclusively in [SCAFFOLD-PRODUCT-SURFACE.md](./SCAFFOLD-PRODUCT-SURFACE.md).

## Dependency direction

```text
foundation contracts
    ├── technical `framework/starters` and `framework/adapters`
    └── provider-owned service APIs
             ↓
business service implementations
             ↓
executable App Assemblies

testing may depend on public seams; production never depends on test support
```

App Assemblies are the only classpath composition roots. Business Services own reusable inbound adapter semantics; App Assemblies select and activate them with runtime capabilities and configuration. A multi-service App also assigns each service's `/api/{service}` External Route Prefix to its public Controllers, while leaving internal Controllers unchanged. A Compose file used by local acceptance does not turn the repository into a production deployment design.

## Framework artifacts

- `moduvera-bom`: dependency-management BOM; it installs nothing.
- `moduvera-kernel`: framework-light package seams for API values, errors, context, authorization, identifiers and transaction contracts.
- `moduvera-web-spring-boot-starter`: MVC/validation/correlation/Problem Details.
- `moduvera-auth-resource-server-autoconfigure`: JWT validation and trusted context entry.
- `moduvera-data-mybatis-plus-spring-boot-starter`: MyBatis-Plus tenant/data wiring and Spring transaction adapter.
- `moduvera-database-migration`: explicit component migration orchestration.
- `moduvera-message-core`: transport-neutral descriptor, Inbox and Outbox contracts/algorithms.
- `moduvera-messaging-kafka-spring-boot-starter`: JDBC stores, Kafka codecs/routes/transport, handler-bound reliable inbound endpoints and relay.
- `moduvera-test-support`: test-only deterministic helpers.

Lock and scheduler artifacts are independent Incubating/Frozen capability experiments; see the product-surface page before consuming them.

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

Each `*-api` owns protocol-neutral Commands, Queries and Views. Each `*-service` owns one or more cohesive business-module roots with `application`, `domain`, `adapter/inbound`, and `adapter/outbound`; service-level `migration` owns only side-effect-free migration definitions. There are no generic top-level `configuration` or `infrastructure` catch-alls. A module's `XxxModuleConfiguration` constructs Application Services only, while public Adapter configuration slices activate concrete transport and infrastructure choices. App Assemblies select those slices and the shared migration execution policy without constructing migration plans or copying resource locations.

For message entry, `XxxInboundConfiguration` registers a `ReliableInboundEndpoint` as the Spring `Consumer` and binds exactly one package-private concrete `XxxMessageHandler`. The Handler implements the `CommandMessageHandler` or `EventMessageHandler` classification interface and calls an Application Service; the Application Service does not implement a transport Handler interface. The outer reliable endpoint owns envelope/contract validation, trusted execution context, bounded retry, transaction and Inbox deduplication. A payload Mapper remains inside `adapter/inbound/messaging` only when serialization or meaning differs under ADR 0031.

Concrete inbound adapters remain package-separated configuration slices in the ordinary Service Jar until a second real transport implementation justifies another artifact. Tenant isolation is infrastructure-derived and fail closed; tenant parameters are not added to business interfaces merely to help persistence.

`order-service` synchronously consumes `catalog-api` through a concrete HTTP client selected by `order-app`. Inventory is asynchronous: Order commits an async command to its Outbox, Inventory atomically deduplicates/reserves/emits a result, and Order atomically deduplicates/applies the terminal state. There are no synchronous `order-client` or `inventory-client` artifacts without real consumers.

## App Assemblies

- `identity-app`: PostgreSQL opaque sessions, tenant memberships/permissions, gateway token exchange, audience-scoped service tokens and JWKS.
- `gateway-app`: edge routes and filters for login and versioned business endpoints; it owns no business Controller and does not expose internal Catalog/Identity/message routes. It strips the service-name prefix only when routing to a standalone service App and preserves it when routing to a multi-service App.
- `catalog-app`: internal JWT-protected product lookup and PostgreSQL persistence.
- `order-app`: public service API behind Gateway, Catalog HTTP client, Order persistence and Kafka result/Outbox assembly.
- `inventory-app`: Kafka-only business entry plus a health endpoint, Inventory persistence and result Outbox assembly.
- `app-monolith`: retained on-demand Catalog/Order/Inventory business-core composition under ADR 0038 and ADR 0027, with compilation but no default runtime qualification. It selects the Local `CatalogApi`, keeps Order/Inventory collaboration on the same Kafka Outbox/Inbox path, and prefixes each service's public Controllers according to ADR 0029. The separate Gateway therefore forwards the stable Order external path without stripping the service name; Gateway and Identity remain separate Apps in this topology.

All six assemblies are eligible inputs to the shared
`build/docker/Dockerfile.jvm`; individual App modules do not own Dockerfiles.
Image construction consumes the executable JAR after Maven packaging and does
not change the assembly dependency direction or define a deployment topology.

The service-to-service security chain is USER JWT at Order, then an audience-scoped SERVICE JWT for Order→Catalog with the original initiator preserved. Catalog derives the tenant from the trusted `Tenant-Id` service header under the framework SERVICE-token rule.

## Verification topology

- Architecture tests enforce framework-free domain, module ownership, inbound/outbound Adapter direction, package-private Command/Event message Handlers, reliable-endpoint ownership, migration definition/execution separation, App Assembly boundaries, production/test isolation, context ownership and no direct business `ThreadLocal`/executor coupling. Negative fixtures prove each structural rule selects a violating shape, while current-scaffold assertions prove the rules select the real production classes.
- PostgreSQL owns end-to-end, failure recovery and default runtime configuration.
- MySQL runs the same focused Repository, tenant, migration and durable-message persistence contracts without multiplying the full topology.
- The Notes consumer proves public artifacts independently. The reference harness defaults to the five-App Golden Path public Gateway HTTP contract, including Kafka outage/restart recovery. Focused Order and Inventory App integration tests prove duplicate-delivery safety. The retained monolith uses the same contract and its own duplicate-delivery IT only on explicit qualification; no test-only production route is added.
- The application-image scenario defaults to building and inspecting the five microservice App images, with monolith image qualification explicitly opt-in, then runs one non-root Catalog image against real PostgreSQL for external-port, Actuator/HTTP and default-off/runtime-opt-in debug behavior. It is an image-construction Scenario gate, not deployment guidance.

## Version governance

Spring Boot 4.1.1 manages the core stack, Spring Cloud BOM 2025.1.3 governs Cloud Stream 5.0.x, MyBatis-Plus is fixed at 3.5.17, and the reactor compiles/releases for JDK 26. The Redisson coordinate may be managed while distributed Lock remains frozen; version governance is not a support claim.
