# Non-Deployment Scaffold Scope

## Outcome

Deliver a reusable Java microservice scaffold under `io.github.ande1922.moduvera` that proves its architectural claims through a runnable multi-process reference business system, selected Golden Path infrastructure, compatibility adapters where explicitly retained, and shared verification suites. Production deployment design and assets remain a separate, open workstream. The local Compose dependency harness is acceptance tooling, not a production topology decision.

The first vertical slice is an implementation sequence, not the final product boundary.

## Completion definition

The non-deployment scaffold is complete only when all of the following exist and pass their relevant verification:

1. a pinned JDK 26/Maven/Spring Boot build with dependency convergence, reproducible JAR inputs, generated SBOMs, and one shared non-root Runnable App image-construction baseline;
2. one framework-free platform kernel with explicit packages for identity, tenant context, identifiers, paging, authorization and errors where genuine cross-module reuse exists;
3. one ordinary-service Web Starter with native HTTP success semantics and RFC 9457 error mapping;
4. internal authentication, resource-server support, tenant membership and use-case RBAC;
5. typed configuration, telemetry integration points, governed HTTP clients and explicit propagation boundaries;
6. a programmatic `TransactionBoundary`, service-owned Flyway artifacts, a MyBatis-Plus adapter, PostgreSQL Golden Path tests, and MySQL compatibility Repository TCKs;
7. at-least-once messaging with CloudEvents, asynchronous command contracts, transactional Outbox/Inbox, and Spring Cloud Stream imperative functions over Kafka in both supported application topologies;
8. Catalog, Order and Inventory reference business capabilities exercising tenant/RBAC, real HTTP boundaries, Outbox/Inbox, PostgreSQL persistence and public black-box acceptance;
9. separate-process Catalog, Order and Inventory App Assemblies with exactly one selected implementation for every required Service API, plus one modular-monolith App Assembly that proves the same reference-business flow through focused topology acceptance;
10. unit, architecture, contract, integration, TCK and independent pytest acceptance suites, with documented evidence and no hidden deployment dependency.

## Explicitly outside this delivery

- container image publication and registry policy;
- Docker Compose as a production or single-host deployment reference;
- Kubernetes manifests, Helm charts and production platform integration;
- deployment pipelines, release promotion and rollout/rollback automation;
- deployment-time health/readiness policy, graceful replacement and capacity topology;
- deployment-side secret injection, image signing/verification and middleware lifecycle ownership.

The repository-owned Dockerfile may package an already-built executable JAR
and the image may expose standard runtime health information and accept
external configuration. Neither that construction contract nor its disposable
smoke environment encodes an unconfirmed deployment topology.

## Capability groups

These groups express the intended completeness model, not current status or a strict chronological sequence. See [the product surface](./SCAFFOLD-PRODUCT-SURFACE.md) for current evidence.

1. Build and framework-light contracts.
2. Technical Starters and in-process implementations.
3. Data, migration and durable messaging reliability.
4. Identity, tenant and authorization closure.
5. Consumer-proven runtime capabilities and governed HTTP clients.
6. Catalog/Order/Inventory multi-process Golden Path.
7. Kafka integration and separate App Assemblies.
8. MySQL compatibility TCK and independent black-box acceptance without an all-combinations matrix.

Each module is introduced with a real interface, behavior and test. Directory shape alone is not evidence of completion.
