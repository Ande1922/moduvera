# Moduvera renaming resource map

This is the final repository-derived rename inventory for publication under
`https://github.com/Ande1922/moduvera`. It supersedes the discarded
`io.github.gaopengcheng.moduvera` draft. Paths and identifiers below were
re-inventoried after the Durable Publication P0 and App assembly topology work
landed in the working tree.

## Brand, owner and coordinates

| Before | Final |
| --- | --- |
| `Gaopc` / `gaopc` | `Moduvera` / `moduvera` |
| GitHub owner `gaopengcheng` | GitHub owner `Ande1922` |
| Maven/Java root `com.gaopc.platform` | `io.github.ande1922.moduvera` |
| discarded draft `io.github.gaopengcheng.moduvera` | `io.github.ande1922.moduvera` |
| `platform-parent` | `moduvera-parent` |
| `platform-dependencies` | `moduvera-bom` |

The root POM is now a reactor-only aggregator, while the reusable build Parent
and standalone consumer BOM live under `framework/parent/` and
`framework/bom/`. Reactor modules resolve to these coordinates:

| Area | Final coordinates |
| --- | --- |
| reactor root | `io.github.ande1922.moduvera:moduvera-reactor` |
| build Parent | `io.github.ande1922.moduvera:moduvera-parent` |
| BOM | `io.github.ande1922.moduvera:moduvera-bom` |
| foundation | `moduvera-kernel`, `moduvera-lock-core`, `moduvera-message-core`, `moduvera-database-migration` under `io.github.ande1922.moduvera` |
| adapters | `io.github.ande1922.moduvera:moduvera-lock-local` |
| starters | `moduvera-auth-resource-server-autoconfigure`, `moduvera-data-mybatis-plus-spring-boot-starter`, `moduvera-messaging-kafka-spring-boot-starter`, `moduvera-scheduler-spring-boot-starter`, `moduvera-web-spring-boot-starter` under `io.github.ande1922.moduvera` |
| testing | `moduvera-test-support`, `moduvera-architecture-testkit`, `moduvera-bom-smoke` under `io.github.ande1922.moduvera` |
| Catalog | `catalog-api`, `catalog-service`, `catalog-app` under `io.github.ande1922.moduvera` |
| Order | `order-api`, `order-service`, `order-app` under `io.github.ande1922.moduvera` |
| Inventory | `inventory-api`, `inventory-service`, `inventory-app` under `io.github.ande1922.moduvera` |
| assembly | `gateway-app`, `identity-app`, `app-monolith` under `io.github.ande1922.moduvera` |
| example | `io.github.ande1922.moduvera.example:simple-notes-demo` |
| benchmark seed | `io.github.ande1922.moduvera.benchmark:store-saas-t01-candidate` |

The branded artifact directory mappings are:

| Before | Final |
| --- | --- |
| `platform-kernel` | `moduvera-kernel` |
| `platform-lock-core` | `moduvera-lock-core` |
| `platform-lock-local` | `moduvera-lock-local` |
| `platform-message-core` | `moduvera-message-core` |
| `platform-database-migration` | `moduvera-database-migration` |
| `platform-auth-resource-server-autoconfigure` | `moduvera-auth-resource-server-autoconfigure` |
| `platform-data-mybatis-plus-spring-boot-starter` | `moduvera-data-mybatis-plus-spring-boot-starter` |
| `platform-messaging-kafka-spring-boot-starter` | `moduvera-messaging-kafka-spring-boot-starter` |
| `platform-scheduler-spring-boot-starter` | `moduvera-scheduler-spring-boot-starter` |
| `platform-web-spring-boot-starter` | `moduvera-web-spring-boot-starter` |
| `platform-test-support` | `moduvera-test-support` |
| `platform-architecture-testkit` | `moduvera-architecture-testkit` |
| `platform-bom-smoke` | `moduvera-bom-smoke` |

Catalog, Order, Inventory, Identity and Gateway artifact names, HTTP routes,
permission codes, logical destinations, Kafka topics/groups and JWT audiences
retain their business meaning.

## Java packages, symbols and beans

| Before package root | Final package root |
| --- | --- |
| `com.gaopc.platform.app.*` | `io.github.ande1922.moduvera.reference.app.*` |
| `com.gaopc.platform.catalog.*` | `io.github.ande1922.moduvera.reference.catalog.*` |
| `com.gaopc.platform.order.*` | `io.github.ande1922.moduvera.reference.order.*` |
| `com.gaopc.platform.inventory.*` | `io.github.ande1922.moduvera.reference.inventory.*` |
| other `com.gaopc.platform.*` | `io.github.ande1922.moduvera.*` |
| `com.gaopc.demo.*` | `io.github.ande1922.moduvera.example.*` |
| `com.gaopc.benchmark.*` | `io.github.ande1922.moduvera.benchmark.*` |

Custom branded Java symbols were renamed as follows; their corresponding test
classes follow the same mapping:

| Before | Final |
| --- | --- |
| `PlatformMonolithApplication` | `ModuveraMonolithApplication` |
| `PlatformResourceServerAutoConfiguration` | `ModuveraResourceServerAutoConfiguration` |
| `PlatformResourceServerConfigurer` | `ModuveraResourceServerConfigurer` |
| `PlatformJwtAuthenticationConverter` | `ModuveraJwtAuthenticationConverter` |
| `PlatformJwtAuthenticationToken` | `ModuveraJwtAuthenticationToken` |
| `PlatformJwtClaims` | `ModuveraJwtClaims` |
| `PlatformDataMybatisPlusAutoConfiguration` | `ModuveraDataMybatisPlusAutoConfiguration` |
| `PlatformMessagingKafkaProperties` | `ModuveraMessagingKafkaProperties` |
| `PlatformMessagingKafkaAutoConfiguration` | `ModuveraMessagingKafkaAutoConfiguration` |
| `PlatformSchedulerAutoConfiguration` | `ModuveraSchedulerAutoConfiguration` |
| `PlatformSchedulerProperties` | `ModuveraSchedulerProperties` |
| `PlatformWebAutoConfiguration` | `ModuveraWebAutoConfiguration` |
| `PlatformArchitectureRules` | `ModuveraArchitectureRules` |
| `PlatformBomConsumerSmokeTest` | `ModuveraBomConsumerSmokeTest` |

Custom Spring bean names now use the `moduvera...` prefix, including the JWT,
resource-server, data/MyBatis, messaging/outbox, scheduler and web beans. The
five Spring Boot auto-configuration import resources and the messaging
`EnvironmentPostProcessor` entry point reference only the final packages.

Framework and JDK names are explicitly outside the brand migration:
`PlatformTransactionManager`, `AbstractPlatformTransactionManager` and
`Thread.ofPlatform()` remain unchanged.

## Configuration, runtime identifiers and metrics

| Before | Final |
| --- | --- |
| `platform.messaging.kafka.*` | `moduvera.messaging.kafka.*` |
| `platform.scheduler.*` | `moduvera.scheduler.*` |
| `platform.identifier.*` | `moduvera.identifier.*` |
| `platform.clients.catalog.*` | `spring.http.serviceclient.catalog.*` |
| `platform.clients.identity.*` | `moduvera.reference.clients.identity.*` |
| `platform.messaging.outbox.*` | `moduvera.messaging.outbox.*` |
| branded thread/bean prefixes `platform...` | `moduvera...` |
| `PLATFORM_JWT_ISSUER` | `IDENTITY_ISSUER_URI` |
| `PLATFORM_JWKS_URI` | `IDENTITY_JWKS_URI` |

The runtime configuration inventory includes
`moduvera.messaging.kafka.{routes,consumer-bindings,relay-enabled,broker-ack-timeout,consumer-backoff-initial,consumer-backoff-max,failure-backoff}`,
`moduvera.scheduler.pool-size`, `moduvera.identifier.worker-id`, the Catalog
Client Group under `spring.http.serviceclient.catalog`, and the Identity client
credentials under `moduvera.reference.clients.identity`.

The final outbox metrics are
`moduvera.messaging.outbox.claimed`,
`moduvera.messaging.outbox.claim.conflicts`,
`moduvera.messaging.outbox.publish`,
`moduvera.messaging.outbox.broker.ack`,
`moduvera.messaging.outbox.stale.token`,
`moduvera.messaging.outbox.cleanup.deleted`,
`moduvera.messaging.outbox.pending`,
`moduvera.messaging.outbox.pending.oldest.seconds`, and
`moduvera.messaging.outbox.terminal`.

## Database, messages, schemas and resources

| Before | Final |
| --- | --- |
| `platform_database_components` | `moduvera_database_components` |
| `platform_message_outbox` | `moduvera_message_outbox` |
| `platform_message_inbox` | `moduvera_message_inbox` |
| `idx_platform_message_outbox_*` | `idx_moduvera_message_outbox_*` |
| `db/platform-messaging/{postgresql,mysql}` | `db/moduvera-messaging/{postgresql,mysql}` |
| `V1__create_platform_messaging.sql` | `V1__create_moduvera_messaging.sql` |
| `META-INF/platform-message-schemas` | `META-INF/moduvera-message-schemas` |

The four final branded outbox indexes are
`idx_moduvera_message_outbox_pending`,
`idx_moduvera_message_outbox_scope_order`,
`idx_moduvera_message_outbox_published_cleanup`, and
`idx_moduvera_message_outbox_terminal_redrive` in both PostgreSQL and MySQL.
Business tables such as `catalog_product`, `order_header`, `order_line`,
`inventory_stock`, `inventory_reservation_result`, `identity_*` and `demo_note*`
retain their domain names.

| Kind | Final identifiers |
| --- | --- |
| message types | `io.github.ande1922.moduvera.reference.inventory.reserve.v1`, `io.github.ande1922.moduvera.reference.inventory.reservation-result.v1`, `io.github.ande1922.moduvera.example.notes.created.v1`, `io.github.ande1922.moduvera.example.notes.failure.v1`; test-only types live below `io.github.ande1922.moduvera.test` |
| logical destinations | `inventory.reserve`, `order.inventory-result`, `notes.events`, `failure.events` are intentionally unchanged |
| sources | branded source URNs use `urn:moduvera:reference:*` and `urn:moduvera:example:*` |
| async media type | `application/vnd.moduvera.async-command+json` |
| message schemas | `urn:moduvera:schema:message:async-command-envelope:1`, `urn:moduvera:schema:message:structured-event-envelope:1` |
| benchmark schemas | `urn:moduvera:schema:benchmark:store-saas-persistence:run-manifest:1.0`, `urn:moduvera:schema:benchmark:store-saas-persistence:run-result:1.0` |

The MyBatis benchmark mapper resources and namespaces now live under
`io/github/ande1922/moduvera/benchmark/store/candidate` and reference the final
package names. Application YAML, deployment Compose/SQL, reference-product
scripts, acceptance tests, benchmark runner/evaluator files, ADRs and product
documentation use the final brand. Generated Maven `target/` trees are ignored
and rebuilt; current SBOMs report the final coordinates.

## Evidence and exclusions

Immutable benchmark smoke evidence, original design-grill transcripts and
scratch ticket inputs retain their historical bytes and may contain the old
names. They are not publication source identities and are excluded from the
active-name gate. Maven Wrapper's `distributionPlatform`, ordinary prose such
as “platform-wide”, Python's standard-library `platform` module, Spring
transaction types and JDK platform-thread APIs are also not brand identifiers.
