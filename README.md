# Moduvera — A Verifiable Java Service Platform

This repository is an executable Spring Boot 4.1.1/JDK 26 platform scaffold plus a non-trivial multi-tenant order-fulfillment reference product. PostgreSQL is the Golden Path, MyBatis-Plus is the selected ORM, Kafka is the selected broker, and MySQL is maintained through focused compatibility tests rather than a second end-to-end matrix. The five-App microservice topology is the Golden Path; a focused business-core modular monolith is also supported behind the same Gateway and Identity trust boundary.

[`SCAFFOLD-PRODUCT-SURFACE.md`](./docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md) is the only current capability-status source. The BOM manages more coordinates than the supported surface; a managed coordinate does not install or prove a capability.

## See the finished product

Requirements: JDK 26, Docker with Compose, and `uv`. Then run:

```bash
verification/reference-product/harness/verify.sh
```

That one command builds and verifies the reactor once, then runs the same public-HTTP-only black-box contract first against five App Assemblies and then against Gateway + Identity + `app-monolith`. Only topology configuration changes the target App set, Order target address and Gateway prefix policy. It proves:

- opaque browser sessions and audience-scoped internal USER/SERVICE JWTs;
- stable 400/401/403/404 behavior without trusting a browser-supplied tenant;
- Gateway → Order → Catalog HTTP with service identity in the Golden Path, and the same `CatalogApi` selected locally inside the business-core monolith;
- transactional Order + Outbox, Kafka command/event flow, Inventory + Inbox + result Outbox;
- `CONFIRMED` and `REJECTED` fulfillment, duplicate safety and correlation propagation;
- concurrent bounded inventory and parallel tenant isolation;
- order creation while Kafka is unavailable, followed by the topology's business App restart and eventual recovery;
- identical external `/api/identity/v1/...` and `/api/order/v1/...` URLs, native success bodies, RFC 9457 errors, external `Location`, correlation and hidden internal/Actuator routes in both topologies.

The harness uses explicit `RUN_SLOT` values so independent runs can coexist on one host. Slot 0 is the compatible default: Apps use ports 58080-58085, PostgreSQL 55432 and Kafka 59092. Every subsequent slot adds a fixed stride of 100 to each selected topology port; for example, `RUN_SLOT=1` uses Gateway 58180, PostgreSQL 55532 and Kafka 59192. Existing `REFERENCE_*_PORT` overrides remain available for focused diagnostics and are normalized to canonical decimal values. The documented verification command first runs its focused harness regressions. Before starting Compose or a JVM, each topology writes a JSON port manifest, prints a human-readable plan, atomically locks the slot and every required host port, and checks IPv4 plus applicable IPv6 wildcard binds so conflicts on any local interface fail early. The default manifest lives in the run's isolated temporary directory; set `REFERENCE_PORT_MANIFEST=/path/to/ports.json` when a caller needs to retain it. Management endpoints currently share each App's HTTP port, and the manifest reports that fact rather than allocating fictitious management listeners. Set `REFERENCE_DEBUG=1` to add deterministic loopback-only JDWP listeners (50080-50085 in slot 0, with the same stride). Compose and owned-lock cleanup failures make an otherwise successful run fail and are reported without replacing an earlier primary failure. Cleanup sends TERM to all JVMs concurrently under one five-second deadline, then gives Compose ten seconds to stop containers; `REFERENCE_APP_STOP_TIMEOUT_SECONDS` and `REFERENCE_COMPOSE_DOWN_TIMEOUT_SECONDS` accept positive integer overrides.

Each topology still receives a unique Compose project, Kafka topics, temporary directory and disposable data volumes, and tears them down after the run. Run only one topology with `verification/reference-product/harness/verify.sh microservices` or `verification/reference-product/harness/verify.sh business-core-monolith`. After a successful build, `REFERENCE_SKIP_BUILD=1` skips the Maven phase.

The full parallel scenario is explicit because it runs both public contracts concurrently. It defaults to isolated slots 40 and 41, supervises each topology and the evidence validator in separate process groups with bounded signal escalation, propagates either failure, and validates distinct ports, Compose projects, data namespaces, Kafka topics and temporary directories from their manifests. The default supervisor grace is the configured JVM deadline plus Compose timeout plus five seconds; its validator timeout is ten seconds. `REFERENCE_PARALLEL_TERM_TIMEOUT_SECONDS` and `REFERENCE_PARALLEL_VALIDATOR_TIMEOUT_SECONDS` accept finite nonnegative overrides.

```bash
verification/reference-product/harness/verify-parallel.sh
```

Pass two distinct slot numbers to override those defaults. Set `REFERENCE_KEEP_PARALLEL_EVIDENCE=1`, or provide a new directory through `REFERENCE_PARALLEL_EVIDENCE_DIR`, to retain the two manifests and topology logs for inspection; otherwise successful temporary evidence is removed after validation. Ordinary `verify.sh [topology]` runs remain sequential and do not implicitly pay for this scenario.

To inspect the selected topology after acceptance, use:

```bash
REFERENCE_KEEP_RUNNING=1 REFERENCE_SKIP_BUILD=1 verification/reference-product/harness/verify.sh business-core-monolith
```

The local fixture users are `alice/alice-password` (`tenant-a`), `bob/bob-password` (`tenant-b`) and `viewer/viewer-password` (read-only in `tenant-a`). These credentials and the Compose assets are for local development/acceptance only and are not a production deployment reference.

## Smaller consumer example

[`examples/simple-notes-demo`](./examples/simple-notes-demo/README.md) is the minimal external-consumer-style example. It has its own Spring Boot parent, imports the Moduvera BOM, declares the relevant Starters directly, and proves Web, JWT, PostgreSQL/MyBatis-Plus, migration, durable messaging, Kafka retry and DLQ behavior through real infrastructure.

## How the scaffold is used

```text
Gateway / message consumer        trusted ExecutionContext boundary
              ↓
Service-owned HTTP or message adapter
              ↓
Application use case → domain → service-owned Repository/API interfaces
              ↓                         ↓
MyBatis-Plus adapter          HTTP client / durable Outbox
              \                         /
                       App Assembly
```

- Put protocol-neutral contracts in `services/<capability>/<capability>-api`.
- Put application/domain logic and service-owned adapters in `<capability>-service`, separated by enforced packages and explicitly importable Spring configuration slices; business interfaces never accept `TenantId` merely for infrastructure isolation.
- Let persistence/message/HTTP adapters derive tenant and correlation from the trusted context and fail closed when it is absent.
- Let each Business Service own reusable Controller and message-Consumer mapping semantics; let `apps/<capability>-app` select and activate those inbound adapters together with migrations and concrete runtime adapters.
- Keep a concrete inbound adapter in the existing `*-service` Jar until a second real transport implementation justifies extracting an `*-adapter-in-*` artifact.
- Keep the Gateway limited to routes and filters; it must not define or duplicate Business Service Controllers. Treat `/api/{service}` as an assembly-owned route prefix: strip it for standalone service targets, but preserve it for a multi-service App that prefixes its own public Controllers.
- Depend directly on the Web, Resource Server, Data or Messaging component an App uses. The BOM supplies versions, not capabilities.
- Do not create a Maven module until it has a real consumer and focused verification; use Java packages for internal separation.

Business code does not need to understand `TenantLineInnerInterceptor`, JWT claim parsing, Kafka headers, Outbox leases, Flyway history naming or MyBatis-Plus Wrapper APIs. Those mechanics stay behind platform or infrastructure seams, while domain-specific Repository and message types remain visible.

## Build and evidence

```bash
./mvnw clean verify
```

The reactor runs unit, architecture, dependency-convergence and real PostgreSQL/MySQL/Kafka integration tests. App packages also produce CycloneDX SBOMs. Common failures are explicit: missing transaction infrastructure, empty/unsafe Kafka routes, missing service credentials, unsupported JWT issuer/audience and absent execution context all fail closed rather than silently disabling isolation.

Design background remains available in [canonical language](./CONTEXT.md), the [decision ledger](./docs/grill/DECISION-LEDGER.md), [ADRs](./docs/adr), and the [structural module map](./docs/implementation/MODULE-MAP.md). Those explain decisions; they do not override the current product-surface page.
