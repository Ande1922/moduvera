# Java Microservice Scaffold Decision Ledger

## Status model

- **Accepted** — directly confirmed by the user or unambiguously confirmed by a later answer.
- **Accepted, verify** — the direction is accepted, but supported status depends on a real Demo/TCK result.
- **Provisional** — the assistant recommended it and the conversation continued, but the same round lacks explicit confirmation.
- **Deferred** — explicitly postponed or removed from the first version.
- **Open** — asked but unanswered.
- **Superseded** — replaced by a later, stronger decision.

## Current ledger

| Area | Status | Decision boundary | Evidence |
|---|---|---|---|
| Product | Accepted | Opinionated internal-first scaffold; reusable versioned components plus runnable reference application | Q1-Q6 |
| Build-time application topology | Accepted, verify | Multi-process microservices remain the Golden Path; a modular monolith is a second supported topology with focused acceptance rather than a duplicated infrastructure matrix; build-time composition stays explicit and runtime topology switching is forbidden | Q4, Q29, Q191; ADR 0022 supersedes ADR 0015 |
| Exact App Assembly modules | Accepted, verify | Separate Catalog, Order and Inventory Apps compose the microservice path; one modular-monolith App Assembly must compose the same business flow behind a single executable entry point | Q329-Q330; refined by ADR 0022 |
| First modular-monolith scope | Accepted, verify | `app-monolith` first composes Catalog, Order and Inventory; Gateway and Identity remain separate trust-boundary Apps for the focused topology, while a future full-backend composition remains possible | 2026-08-31 App Assembly review; ADR 0027 |
| Gateway role | Accepted, verify | Gateway contains routes and filters only; Business Service Interfaces layers uniquely own HTTP Controllers, the existing handwritten `GatewayController` must be removed, and service-name prefixes are handled only by route/assembly configuration | 2026-08-31 App Assembly review; ADR 0028 and ADR 0029 |
| Service API | Accepted, verify | Protocol-neutral `*-api`; remote HTTP clients implement cross-App calls in the microservice path and Application Services implement Local calls in the modular-monolith path without multiplying the full infrastructure matrix | Q24, Q27-Q29, Q40-Q43; refined by ADR 0022 |
| Authentication | Accepted | Internal username/password authorization service and tenant-level RBAC | Q15, Q20-Q33 |
| Token model | Accepted | Opaque browser token; one gateway exchange to short-lived internal JWT; resource services revalidate | Q34-Q39 |
| Tenant boundary | Accepted | HTTP/message/job boundaries establish a trusted Tenant Context; infrastructure capabilities consume it and fail closed, while routine Application/Domain/Repository interfaces remain tenant-transparent unless tenant identity has business meaning | Q18, Q21-Q23, Q73, Q103-Q104; ADR 0003 refined by the 2026-08-30 review |
| Tenant database safeguards | Accepted, verify | Persistence records and constraints retain portable `tenant_id` isolation; adapters inject and filter from the trusted context; important same-service relations use tenant-aware constraints; RLS is optional defense in depth | Q220, Q224-Q226; ADR 0003 refined by the 2026-08-30 review |
| RBAC authorization point | Accepted | Permission enforcement occurs at the Application use-case boundary, not only in controllers | Q25-Q33 |
| Lightweight CQRS | Accepted | Command and Query Application Services; write model uses Domain; Query can project Views directly | Q45-Q47, Q152-Q159 |
| Domain event handling | Accepted | Domain behavior returns explicit business results; automatic event collection is not mandatory | Q151 |
| Code organization | Accepted | Service -> layer -> business -> responsibility; stable framework-free platform primitives share one `platform-kernel` artifact with explicit packages, but no ungoverned `common/utils` dumping ground, empty packages, or `ServiceImpl` pairs | Q142-Q160; refined by ADR 0018 |
| Detailed framework boundaries | Accepted, verify | Application directly implements Service API; Domain/Data types remain separate; Domain stays framework-free; each providing Business Service owns reusable inbound Controller/Consumer mapping semantics in explicit `*-service` packages/configuration slices, while leaf App Assemblies select and activate adapters | Q152-Q156, Q227; refined by ADR 0025 and ADR 0026 |
| Common fields and AggregateVersion | Accepted, verify | Persistence records declare audit, tenant and version fields explicitly; no shared `BaseEntity`, universal Mapper or Repository; the exact AggregateVersion shape remains a Demo decision | Q157, Q176; refined by ADR 0018 |
| Repository tenant API | Accepted | Ordinary business Repositories do not expose `TenantId` solely for isolation; persistence adapters enforce the current trusted tenant, while cross-tenant administration uses separate explicitly authorized interfaces | Q181 superseded by ADR 0003's 2026-08-30 refinement |
| Web packaging | Accepted | Web concerns and OAuth2 Resource Server concerns remain separate components; each App explicitly selects the Web and security starters it needs, while Gateway and Identity remain executable App Assemblies | Q48; refined by ADR 0018 |
| HTTP success/error semantics | Accepted | Unwrapped success; real status; RFC 9457 body with stable code/correlation and field errors where applicable | Q51, Q57 superseded by Q344, Q335-Q353 |
| API version and route-prefix ownership | Accepted, verify | Business Service Controllers own major-version and missing-version behavior and declare service-local paths; `/api/{service}` is an assembly-owned external route prefix. Gateway strips it for standalone service targets, while a multi-service App prefixes the matching public Controllers and receives the unstripped path; exactly one transformation is active | Q331-Q341; refined by ADR 0028 and ADR 0029 |
| Configuration and secrets | Accepted, verify | Typed configuration; dynamic-refresh allowlist; Nacos encrypted settings with bootstrap secrets externalized | Q49, Q55-Q56 |
| Telemetry | Accepted, verify | OTel Agent for automatic telemetry and Micrometer for application metrics; avoid duplicate bridges | Q50, Q59 |
| Messaging reliability | Accepted, verify | Both supported topologies use the same Kafka-backed at-least-once path with transactional Outbox/Inbox, retry classification, DLQ and bounded ordering; no Local Transport is added | Q61-Q107; refined by ADR 0024 |
| Message contract | Accepted | Structured CloudEvents for events; separate Asynchronous Command envelope; Schema artifacts published with APIs | Q69-Q78, Q100-Q101 |
| Broker and programming model | Accepted, verify | Spring Cloud Stream imperative handlers with the Kafka Binder are used in both supported topologies; a future middleware implementation must remain behind the messaging boundary and pass the same semantic contracts | Q81-Q102; ADR 0017 refined by ADR 0024 |
| HTTP Client governance | Accepted | Boot Client Groups, Apache HC5, caller-owned timeout/concurrency/retry/circuit policy, one retry layer | Q307-Q328 |
| Cross-service deadline | Deferred | No remaining-budget propagation in first version | Q317 |
| Generic request idempotency | Deferred | Business idempotency first; no generic annotation/template until a dedicated design round | Q117, Q121 |
| Distributed lock | Accepted | Explicit Lock abstraction; Local and Redisson first; bounded acquisition retry; callback executes at most once | Q136-Q138 |
| Distributed transaction/runtime orchestration | Deferred | No XA/Seata, Process Manager runtime, workflow DSL, or generic compensation engine in core | Q132-Q143 |
| Identifier | Accepted | Numeric Snowflake-like `long/BIGINT`; JSON string; pluggable unique worker identity; fail on unsafe rollback | Q175, Q178-Q180 |
| Database and persistence matrix | Accepted, verify | MyBatis-Plus is the only selected persistence framework; PostgreSQL is the Golden Path and MySQL is a long-term compatibility target verified by the same Repository contracts; jOOQ is deferred | Q202-Q219; superseded by ADR 0016 |
| Transaction boundary | Accepted | Framework-free `TransactionBoundary.inTransaction` defines one top-level, single-service, single-database transaction; only local repositories and same-database Outbox/Inbox are allowed inside; Spring implementation belongs to the MyBatis-Plus Data Starter; no HTTP, broker or Redis side effects | Q227-Q231; name and ownership refined by the 2026-08-30 architecture review |
| Data conflict semantics | Provisional | Rollback failures throw; optimistic locking is aggregate-specific; pessimistic locking uses purpose-named Repository operations | Q232-Q234 |
| Database migration | Accepted | Service-owned logical database, schema/migration artifact, identity, Flyway history, Expand/Contract and Forward Fix semantics | Q240-Q259 |
| Migration execution orchestration | Open | Who runs service-owned migrations, in which release order and with which privileges belongs to the unconfirmed deployment design | Q419-Q420 and the user's 2026-08-30 clarification |
| String/search semantics | Accepted, verify | Default accent/case-sensitive storage; normalized identities; explicit human-search CI; specialist index for complex search | Q260, Q264-Q267 |
| Cache API | Accepted | Spring Cache annotations and native Redis access; no custom universal CacheTemplate or automatic L1+L2 | Q268-Q290 |
| CacheDefinition backend routing | Accepted, verify | Named cache definition selects Redis or Caffeine with startup validation | Q276-Q282 |
| Cache serialization and fallback defaults | Provisional | JSON without Java class metadata; negative caching opt-in; cache failure returns to the authoritative source; every cache has bounded lifetime/capacity | Q278-Q282 |
| Cache stampede | Accepted | All scaffold-managed cacheable loads use same-process single-flight, not a distributed lock | Q283 |
| Scheduler | Accepted | Fixed-capacity scheduler with fixed-delay/Cron; virtual threads only inside explicitly bounded jobs | Q291-Q306 |
| Job execution defaults | Provisional | Overlap skips rather than waits; transaction is per batch/use case; Cron, enablement and timezone are external with UTC default | Q295-Q297 |
| Object upload and lifecycle | Accepted | Small stream/large presign; server key; UploadPolicy; prepare/confirm; PENDING lifecycle; public/private separation | Q357-Q369 |
| Object reference details | Provisional | Logical Bucket Alias, ObjectRef, download mode, checksum model, optional version ID | Q370-Q374 |
| Image processing | Deferred | Compression, thumbnails, and format conversion remain outside the first-version OSS capability | Q364 |
| Testing model | Accepted, verify | Layered Java tests plus independent pytest black-box acceptance; real dependencies; bounded asynchronous assertions; no automatic flaky-test reruns | Q375, Q381-Q390 |
| Test matrix and CI cadence | Provisional | PostgreSQL runs the Golden Path; the same MyBatis-Plus Repository and migration contracts verify MySQL compatibility without multiplying all end-to-end scenarios; exact PR/main/scheduled cadence remains evidence-driven | Q376-Q380; refined by ADR 0016 |
| Quality tooling | Accepted, verify | Spotless only for formatting; selected PMD and compiler warnings; changed-code coverage and CRAP gates; PIT is optional and targeted | Q391-Q397 |
| Reproducible build and supply chain | Accepted, verify | Pinned Maven/plugin/dependency inputs, Enforcer, reproducible JAR inputs, per-App and aggregate SBOM, and grouped dependency updates | Q398-Q405 |
| Container packaging | Deferred | Historical Q406-Q415 recommendations are outside the current non-deployment delivery boundary; revisit with deployment design | Q406-Q415, overridden for current scope by the user's 2026-08-30 clarification |
| Image signing | Deferred | Do not preconfigure Cosign or signature verification until a project has a deployment-side verification requirement | Q416 |
| Deployment baseline | Open | Production/single-host Compose, Kubernetes, Helm, middleware ownership, migration orchestration, and rolling-release behavior remain unconfirmed; a disposable local Compose dependency harness is accepted only as reference-product test tooling | Q417-Q424, excluding withdrawn Q423; reopened and later narrowed by direct instructions on 2026-08-30 |
| Supported Local assembly | Accepted, verify | Local invocation is required by the modular-monolith topology, which must pass focused end-to-end acceptance without becoming a second full infrastructure matrix | Q421; ADR 0022 supersedes ADR 0015 |

## Superseded decisions that must not leak into implementation

- Q205-Q219's jOOQ/MyBatis-Plus 2 x 2 matrix is superseded by ADR 0016: MyBatis-Plus is selected, PostgreSQL is primary, and MySQL remains a compatibility target.
- PostgreSQL-only/RLS-first tenancy is still superseded by portable tenant semantics; PostgreSQL being primary does not make RLS the portable tenant contract.
- Explicit `TenantId` parameters on ordinary business Repository methods are superseded by ADR 0003's refinement: trusted context is explicit at entry/infrastructure seams and transparent to routine business callers.
- Equal Kafka/RocketMQ support is superseded by ADR 0017; Kafka is the current Binder target and other brokers are deferred.
- Modular-monolith-first delivery remains superseded: ADR 0022 keeps multi-process microservices as the Golden Path while replacing optional Local composition with a required, focused second topology.
- RBAC-out-of-scope is superseded by the internal identity and tenant RBAC decision.
- Browser JWT-with-all-permissions is superseded by opaque browser tokens and internal JWT exchange.
- The explanatory custom `MessageEnvelope` was explicitly rejected before CloudEvents was designed.
- Spring Modulith and the generic Process Manager runtime are outside the first-version core.
- Handler-per-use-case and generic Port packages are superseded by simplified DDD and purpose-named interfaces.
- Automatic `releaseDomainEvents()` is superseded by explicit domain results.
- The custom CacheTemplate, distributed cache stampede lock, and two-default-Redis-factories proposals are superseded.
- Virtual-thread scheduling is superseded by a fixed-capacity scheduler.
- The custom HTTP client factory is superseded by Boot-native Client Groups.
- Gateway version stripping is superseded by service-owned API versioning; Gateway may strip only the distinct `/api/{service}` routing prefix when targeting a standalone service App.
- Q57's `ProblemDetail.type`-only error identity is superseded by the later `code`, `correlationId`, and validation `errors` shape.
- Mandatory database records for all objects are superseded by object-metadata-first ownership.
- A scaffold-owned device simulator is superseded by protocol-specific simulators owned by later business integration work.
- Optional Cosign scaffolding is removed from the first version.
- Q423 is withdrawn as a duplicate; it does not create a second migration-rollback decision.

Numeric examples such as connection-pool size, millisecond timeouts, upload threshold, pending lifetime, cache TTL, cache capacity, changed-code coverage, and CRAP threshold remain configurable baselines until measured; they are not architectural constants.
