Status: ready-for-agent

# 收紧基础运行时能力并移除无消费者的对象存储契约

## Problem Statement

当前脚手架的部分基础能力已经被真实参考产品使用，但其默认运行方式仍存在安全或一致性缺口。Snowflake Identifier 的 Worker ID 曾有隐式默认值，容易在多实例部署时产生重复身份；数据库迁移在多个持久化 App 中默认以 `STARTUP + initialize=true` 运行，使业务运行身份天然拥有初始化和 DDL 能力；Identity Demo 每次启动生成临时签名密钥，但产品状态又把生产密钥能力列为待建设事项，混淆了 Demo 与正式产品边界。

HTTP 和权限错误处理也存在重复或决策漂移。应用用例抛出的 `PermissionDeniedException` 需要每个 Business Service 重复映射为 403，而 Spring Security 入口的 401/403 已有统一 Problem Details。Order 到 Catalog、Order 到 Identity 的真实远程调用仍手写 `RestClient`，尚未落实已接受的 Spring Boot HTTP Service Client Group 决策，导致 base URL、连接生命周期、超时和启动校验由调用者自行拼装。

另一方面，缓存、分布式锁、调度和对象存储曾被提前设计成通用能力，但只有 Service Token 缓存具有真实消费者。Lock 与 Scheduler 只有自身测试和相互依赖，没有参考产品消费者；Object Storage 甚至没有 Adapter 或业务调用者。继续扩展这些浅模块会把未经业务语义验证的接口固化成公共产品承诺。

需要把基础能力按两条规则重新收紧：已有真实消费者的能力必须补齐安全默认值和可执行治理；没有真实消费者的能力不得继续扩大公共表面积。对象存储的现有定义应直接撤回，未来从具体业务场景重新设计。

## Solution

保持 Worker ID 为 App Assembly 显式提供的必填环境配置，并继续由 Snowflake Identifier Generator 校验合法范围；不引入数据库或 Redis Worker 租约。持久化 App 的正式启动策略改为 `VALIDATE`：启动时验证 Database Component Identity、Flyway 历史和待执行迁移，但绝不创建身份记录、baseline 或执行 DDL。只有拥有 disposable database 的测试和 reference-product harness 显式选择 `STARTUP + initialize=true`。生产迁移由未来部署方案中的显式发布步骤执行，本规格不创建没有部署消费者的一次性迁移 CLI。

Identity、Catalog、Order、Inventory、Monolith 和 Notes 统一通过 Service-owned Migration Definition 与 Migration Starter 参与迁移策略，删除 App 内手写迁移执行。Identity 的内存临时签名密钥继续作为 Demo 实现，不在本规格中发展成生产密钥存储、轮换或多实例能力，并在产品状态中明确其非生产边界。

Web Starter 提供应用层 Permission Denial 的默认 Problem Status 映射。`PermissionDeniedException` 返回 403，并保留 `security.permission-denied` 业务错误码；Spring Security 过滤器拒绝仍使用 `security.forbidden`。Business Service 只贡献自身 Not Found 等业务异常映射，不再复制平台权限异常规则。

把 Order 的两个真实出站 HTTP 调用迁移到 Spring Boot 4.1 原生 HTTP Service Client Group。Catalog 与 Identity 分属具名 Client Group，共享各自的 base URL、Apache HC5 连接池、连接/读取超时和 Observability。协议中立的 `CatalogApi` 保持不变；Outbound HTTP Adapter 继续负责 Execution Context、Tenant、Correlation、Service Token 和业务错误转换。保留现有按安全作用域缓存的 Service Token，不创建通用 HTTP Client Starter，也不加入缺乏故障与吞吐证据的自动重试、CircuitBreaker 或额外并发限制。

保留 Lock 与 Scheduler 实验模块，但将其标记为 Incubating/Frozen；在真实业务消费者出现前不增加分布式 Adapter 或扩大接口。Service Token 缓存保持 Order 私有实现，不提升为通用 Cache 能力。完整删除无消费者的 Object Storage API、BOM/Reactor 坐标、测试和当前有效设计；历史 grilling transcript 仅作为审计记录保留。

## User Stories

1. As a Deployment Operator, I want every Snowflake-generating App instance to require an explicit Worker ID, so that two instances do not silently share a default generator identity.
2. As a Deployment Operator, I want an invalid Worker ID to fail during startup, so that an unsafe identifier configuration never serves traffic.
3. As a Deployment Operator, I want Worker ID allocation to remain an external deployment responsibility, so that the scaffold does not introduce Redis or database availability into ID generation.
4. As a Local Developer, I want the reference harness to supply its Worker ID explicitly, so that local verification remains reproducible after removing defaults.
5. As a Business Service Owner, I want normal application startup to validate database compatibility without executing DDL, so that the runtime identity can use least privilege.
6. As a Business Service Owner, I want an uninitialized Database Component to fail startup in validation mode, so that the application does not run against an unknown database.
7. As a Business Service Owner, I want a mismatched Flyway history to fail startup, so that incompatible application and schema versions are detected before traffic arrives.
8. As a Business Service Owner, I want pending migrations to fail startup in validation mode, so that an application cannot silently run on an older schema.
9. As a Release Operator, I want migration execution to require an explicit release policy, so that database changes cannot happen merely because a business process restarted.
10. As a Security Engineer, I want normal application credentials to avoid schema-initialization privileges, so that a compromised runtime has a smaller database blast radius.
11. As a Test Maintainer, I want integration tests to opt into startup initialization explicitly, so that disposable database setup remains convenient without weakening production defaults.
12. As a Reference Product Maintainer, I want the acceptance harness to opt into startup initialization explicitly, so that both supported topologies can still provision fresh local databases.
13. As an Identity Demo Maintainer, I want Identity migrations to use the same Migration Definition and execution policy as other persistent Apps, so that Identity does not bypass repository migration rules.
14. As a Notes Consumer Maintainer, I want the independent Notes example to consume the public Migration Starter, so that it proves the supported integration instead of calling an internal executor directly.
15. As an Architecture Maintainer, I want Service-owned Migration Definitions to remain side-effect free, so that deployment policy stays in the App Assembly or release composition.
16. As a Platform Maintainer, I want no speculative migration CLI in this change, so that deployment mechanics are defined by a real deployment consumer later.
17. As an Identity Demo User, I want documentation to state that ephemeral signing keys are Demo-only, so that I do not mistake the reference Identity for production IAM.
18. As a Platform Maintainer, I want production key rotation and multi-instance key management excluded from the current roadmap, so that the scaffold does not predesign an IAM product it does not own.
19. As an API Consumer, I want an application-level permission denial to consistently return HTTP 403, so that authorization failures do not appear as generic 422 responses.
20. As an API Consumer, I want `security.permission-denied` to remain the application authorization error code, so that clients and diagnostics can distinguish use-case rejection from authentication-filter rejection.
21. As a Security Engineer, I want Spring Security access denial to keep the `security.forbidden` code, so that filter-chain enforcement and application use-case enforcement remain distinguishable.
22. As a Business Service Developer, I want the Web Starter to own the default Permission Denial mapping, so that every service does not repeat the same infrastructure rule.
23. As a Business Service Developer, I want service-specific Not Found mappings to remain provider owned, so that generic Web behavior does not guess domain status semantics.
24. As a Notes Consumer Developer, I want the same default Permission Denial behavior as repository Business Services, so that independent consumers receive the supported Starter contract.
25. As an Order Developer, I want Catalog remote calls to use a declarative HTTP Service interface, so that URI and transport definitions are explicit and centrally configured.
26. As an Order Developer, I want Identity token calls to use a separate declarative HTTP Service interface, so that credentials and token semantics do not leak into the Catalog group.
27. As an Order Developer, I want `CatalogApi` to remain protocol neutral, so that the modular-monolith topology can continue selecting a Local implementation.
28. As a Monolith Maintainer, I want the Local Catalog path to remain free of HTTP Client dependencies, so that topology selection stays an App Assembly concern.
29. As an Operations Engineer, I want Catalog and Identity base URLs to be required and checked at startup, so that incomplete remote configuration fails before the first request.
30. As an Operations Engineer, I want connect and read timeouts configured per Client Group, so that each downstream dependency has an explicit latency budget.
31. As an Operations Engineer, I want Apache HC5 connection pooling used by the real remote callers, so that connection lifecycle is governed rather than recreated ad hoc.
32. As an Observability Engineer, I want native HTTP Client observations to remain available, so that downstream latency and failures can be diagnosed with the Spring observability stack.
33. As a Security Engineer, I want Tenant, Correlation and Service Token headers established by the Outbound Adapter, so that declarative transport does not weaken the trusted execution-context boundary.
34. As an Order Developer, I want Service Tokens cached by service, audience, tenant and initiator scope, so that Identity load is reduced without crossing security boundaries.
35. As an Order Developer, I want token refresh failure to fail closed instead of using a stale token, so that remote calls never continue with expired authority.
36. As a Reliability Engineer, I want HTTP calls to have no automatic retry in the initial migration, so that non-idempotent Identity token requests are not repeated by a generic policy.
37. As a Reliability Engineer, I want CircuitBreaker and extra concurrency limits deferred until runtime evidence exists, so that recovery behavior reflects actual dependency failure modes.
38. As a Framework Maintainer, I want no generic HTTP Client Starter from only one calling service, so that a second independent consumer determines the reusable seam.
39. As a Framework Maintainer, I want the Service Token cache to remain consumer-specific, so that a security-token policy is not mislabeled as a universal Cache API.
40. As a Framework Maintainer, I want Lock and Scheduler public surfaces frozen without a business consumer, so that tests of self-contained abstractions do not count as product evidence.
41. As a Future Lock Consumer, I want distributed-lock semantics designed from my concrete correctness requirements, so that lease, fencing and failure behavior are not inherited from a hypothetical use case.
42. As a Future Scheduler Consumer, I want overlap, tenancy and transaction semantics derived from a real Job, so that the scheduler contract does not prematurely constrain application behavior.
43. As a Repository Maintainer, I want the speculative Object Storage module removed, so that an unverified API is not published through the Reactor or BOM.
44. As a Future Object Storage Consumer, I want storage terminology and contracts redesigned from concrete attachment or media behavior, so that Bucket, visibility, checksum and presign details match the actual domain.
45. As a Documentation Reader, I want current product status to distinguish Supported, Incubating, Deferred and Withdrawn capabilities, so that module presence is not mistaken for support.
46. As a Documentation Reader, I want historical grilling records preserved but clearly non-normative, so that decision history remains auditable without reviving withdrawn designs.
47. As an AI Coding Agent, I want consumer evidence to be the promotion rule for reusable infrastructure, so that generated changes do not create new shallow modules from speculative variation.
48. As a Human Reviewer, I want broad runtime changes verified at existing product seams, so that implementation structure is not mistaken for externally correct behavior.
49. As a Downstream BOM Consumer, I want removed Object Storage coordinates to disappear consistently, so that the BOM never advertises an artifact the Reactor no longer builds.
50. As a Repository Maintainer, I want unrelated working-tree changes preserved, so that this hardening effort remains isolated from concurrent architecture work.

## Implementation Decisions

- Worker ID remains a required App Assembly configuration sourced from `MODUVERA_IDENTIFIER_WORKER_ID` for the current Order-producing topologies. There is no default value.
- Snowflake Identifier Generator remains responsible for the accepted numeric range and unsafe-clock-rollback behavior. This work does not create a Worker registry, lease, database table or Redis dependency.
- Tests and the reference harness use explicit Worker IDs. A single harness may reuse the same ID across mutually exclusive topologies, but concurrently running generator instances must receive distinct values from their deployment composition.
- Add `VALIDATE` to the database migration execution modes. Every persistent reference App selects `VALIDATE` in its normal configuration.
- `VALIDATE` verifies every selected Migration Definition without performing writes. It verifies Database Component Identity, Flyway history validity and that no migration remains pending. Missing identity, missing history where required, invalid history or pending work fails application startup.
- `VALIDATE` must reject initialization options. It cannot create the component identity table or row, baseline a schema, repair history or run migrations.
- `STARTUP` remains the explicit executor mode. `initialize=false` only migrates a database whose component identity is already established; `initialize=true` may establish identity and initialize a disposable or explicitly approved database.
- `STARTUP + initialize=true` is selected only by integration tests and the reference-product harness in this specification. It is not a normal App default.
- `EXTERNAL` and `DISABLED` do not gain an embedded migration CLI. Production release execution remains part of a future deployment composition, while this specification ensures normal runtime behavior is safe and fail-fast.
- Catalog, Order and Inventory continue publishing side-effect-free Service-owned Migration Definitions. Identity and the independent Notes consumer are converted to the same Definition plus Migration Starter model and stop directly constructing `DatabaseMigrator`.
- Messaging continues owning its own Migration Definition. A multi-service App selects it once alongside its Business Service definitions.
- Existing schemas, migration locations, component names and Flyway history-table identities remain unchanged. This feature changes execution policy, not database schema.
- The reference Identity continues generating an ephemeral in-memory RSA signing key and random key ID. This is explicitly Demo evidence, not a supported production-key capability.
- Production key persistence, secret-store integration, key rings, rotation, overlapping verification windows and multi-instance Identity operation are not implemented or promised by this specification.
- Web Starter supplies a lowest-precedence default `ProblemStatusContributor` or equivalent resolver rule for `PermissionDeniedException`, mapping it to HTTP 403.
- Provider-owned Problem Status contributors continue to take precedence for service-specific exceptions. The generic permission mapping eliminates duplicate branches but does not absorb business Not Found or conflict semantics.
- `PermissionDeniedException` remains a coded application exception with `security.permission-denied`. Spring Security `AuthenticationEntryPoint` and `AccessDeniedHandler` responses retain `security.unauthenticated` and `security.forbidden` respectively.
- Order, Catalog and Notes remove their repeated application-permission status branches while preserving all service-specific mappings and existing Problem Details structure, including Correlation ID.
- Keep `CatalogApi` transport neutral and unchanged. The modular-monolith continues selecting the Local Catalog implementation.
- Add transport-specific declarative interfaces for Catalog lookup and Identity service-token issuance inside the Order Outbound HTTP Adapter boundary. They are not published from provider API modules and do not carry Spring annotations into `CatalogApi`.
- Register two Spring Boot 4.1 HTTP Service Client Groups named `catalog` and `identity`. Each group owns its base URL and timeout configuration.
- Move remote client configuration to the Spring Boot `spring.http.serviceclient` group namespace. Legacy custom base URL and timeout keys are removed or migrated consistently across Apps, tests and the reference harness.
- Missing or unusable Client Group base URLs fail during application startup rather than on the first business request.
- Apache HC5 is the selected blocking HTTP transport and connection-pool implementation for these groups. Native Spring observations remain enabled.
- The Catalog Outbound Adapter continues to obtain the trusted Execution Context and set authorization, Tenant and Correlation headers before calling the declarative transport.
- The Identity token transport continues using the current Basic client authentication plus tenant, audience and original initiator request body. It is not converted to a standard OAuth2 client-credentials abstraction because its contract carries delegated context.
- The existing Service Token cache remains behind `InternalAccessTokenProvider`. Its key includes service ID, audience, Tenant and Initiator identity; it uses expiration-aware refresh skew, same-key single-flight, bounded entries and fail-closed refresh.
- Transport errors and non-success statuses continue to map into the existing Order-side Catalog call failure semantics. Declarative transport must not leak Spring HTTP exceptions into Application or Domain code.
- No automatic retry is enabled for either Client Group. No CircuitBreaker, bulkhead or additional concurrency limiter is introduced in this change.
- Do not create a framework HTTP Client Starter or generic client factory. Revisit extraction only when another independent Business Service caller establishes a shared variation point.
- Do not create a universal Cache abstraction. The only current production cache remains the private Service Token cache driven by the Order-to-Identity consumer path.
- Retain `moduvera-lock-core`, `moduvera-lock-local` and `moduvera-scheduler-spring-boot-starter` as Incubating/Frozen artifacts. Do not change their APIs, add Redisson/JDBC lock implementations or promote their support status in this work.
- Remove the complete `moduvera-object-storage-api` artifact, its public types and its self-contained contract test.
- Remove Object Storage from the Reactor, BOM, BOM consumer smoke, module map, renaming inventory, product roadmap and current capability documentation.
- Mark the previous object upload, lifecycle and reference decisions Withdrawn. Remove Object Storage-specific glossary terms from the canonical Context.
- Preserve the historical grilling transcript and question index without editing past conversation. They remain historical evidence and are not current product guidance.
- Keep the accepted consumer-promotion rule: module existence, a POM, an interface or a self-test does not establish Supported capability status.
- No implementation ticket decomposition is created by this specification; it is published as one ready-for-agent specification, and later planning may split work by dependency frontier.

## Testing Decisions

- A good test observes startup acceptance/rejection, HTTP status and Problem Details, remote call behavior, security-context propagation, database state or supported topology behavior. Tests should not assert private helper calls, proxy class names or internal Spring factory choices unless those are the only reliable evidence of a public contract.
- Reuse existing seams instead of creating test-only interfaces. The highest product seam is the topology-parameterized public HTTP acceptance harness, which runs the same contract against the microservices and business-core modular-monolith topologies.
- Migration Starter runtime integration tests are the primary seam for execution policy. They must cover successful `VALIDATE`, missing Database Component Identity, invalid history, pending migration, and the guarantee that validation performs no schema or identity writes.
- Migration tests must prove `VALIDATE` rejects initialization, while explicit `STARTUP + initialize=true` still initializes a fresh disposable PostgreSQL and MySQL database where those dialects are already supported.
- Each persistent App integration test must prove its normal assembly can start with a fully migrated database under `VALIDATE`. At least one focused startup test must prove an uninitialized or pending database prevents readiness.
- Identity integration tests must prove migration behavior after removing its hand-written executor. Existing login, exchange, service-token and JWKS behavior remains regression coverage for the Demo.
- The independent Notes consumer remains the highest external-consumer seam for BOM and Starter usability. Its tests must prove migration, authorization error mapping and existing HTTP/messaging behavior without direct `DatabaseMigrator` construction.
- Web Starter contract tests are the primary seam for default application Permission Denial mapping. They must assert HTTP 403, RFC 9457 response structure, stable error code and Correlation ID.
- Provider contributor tests must prove service-specific statuses still take precedence and that removing duplicate permission branches does not change Order or Catalog Not Found behavior.
- Resource Server tests remain prior art for `security.unauthenticated` and `security.forbidden`; they should confirm the new application mapping does not change filter-chain responses.
- Order Service focused tests are the primary fast seam for declarative Catalog and Identity adapters. They must cover header propagation, request shape, success decoding, empty responses, non-success status conversion and transport failure conversion.
- Service Token cache tests remain the fast seam for security-scope isolation, expiration skew, bounded capacity, same-key single-flight and failure without stale-token fallback.
- Order App integration tests are the highest seam for the real Identity-token-to-Catalog call chain. They must prove repeated same-scope Catalog calls reuse a valid token and that declarative Client Groups preserve Tenant, Initiator and Correlation semantics.
- Client configuration tests must prove both `catalog` and `identity` groups require base URLs and honor independent connect/read timeout settings. Tests should observe startup behavior and request outcomes rather than private proxy construction.
- HTTP transport qualification must verify Apache HC5 is the selected runtime client and pooled connections are configured through supported Boot mechanisms. Avoid assertions against undocumented internal class names when an observable configuration or runtime seam is available.
- No retry tests, CircuitBreaker tests or concurrency-limit tests are added because those policies are explicitly out of scope.
- The modular-monolith integration test must remain free of Remote Catalog Client Group requirements, proving Local `CatalogApi` selection is unchanged.
- Reactor and BOM smoke tests must prove the removed Object Storage artifact is absent and all remaining published coordinates still resolve versionlessly.
- Repository searches and architecture checks must prove no production or test code imports the withdrawn Object Storage types and no current product document advertises the removed capability.
- Lock and Scheduler existing tests continue passing unchanged. No new behavior tests are added because their public surfaces are frozen.
- Run the narrowest affected module tests first: database migration foundation and Starter, Web Starter, Order Service, Identity App and Notes consumer.
- Expand verification to Catalog, Order, Inventory and Monolith App integration tests, architecture testkit and BOM smoke.
- Completion requires a current full-reactor `clean verify`, not compilation alone.
- Completion also requires the reference-product harness to pass both supported topologies with explicit Worker ID and explicit disposable-database initialization configuration.
- Existing PostgreSQL is the Golden Path; MySQL coverage remains focused on the already supported Migration and persistence contracts rather than duplicating the full topology matrix.

## Out of Scope

- Database- or Redis-backed Worker ID allocation, leases, duplicate-instance detection or Kubernetes-specific assignment templates.
- Changing Snowflake bit allocation, epoch, JSON representation or clock-rollback policy.
- Building a production deployment system, Helm chart, Kubernetes Job or standalone migration CLI.
- Defining release ordering across independently deployed services beyond the existing Service-owned Migration Definition boundary.
- Changing any existing business database schema, migration version or Database Component identity.
- Production Identity signing-key persistence, KMS/Vault integration, automatic rotation, historical JWKS retention or multi-instance operation.
- Replacing the reference Identity with a production IAM product or expanding scaffold ownership of user directories, credentials, RBAC or License policy.
- Changing authentication-filter 401/403 behavior or public business error codes other than correcting application Permission Denial status mapping.
- Changing `CatalogApi`, public HTTP routes, public request/response schemas or modular-monolith Local invocation semantics.
- Creating a generic HTTP Client Starter, custom client factory or new provider client artifact.
- Automatic HTTP retries, CircuitBreaker, bulkhead, rate limiter, request hedging or cross-service deadline propagation.
- Standard OAuth2 client-credentials conversion for the delegated Identity service-token request.
- Creating a generic Cache abstraction, Spring Cache governance layer, Redis cache Adapter or multi-level cache.
- Adding Redisson/JDBC distributed locks or extending Lock/Scheduler behavior.
- Deleting Lock or Scheduler artifacts; they remain Incubating/Frozen.
- Replacing the withdrawn Object Storage API with another storage design, S3 Adapter, attachment model or presigned-upload workflow.
- Rewriting or deleting historical grilling transcripts and question indexes.
- Splitting this specification into implementation tickets, committing changes, publishing artifacts or deploying any runtime.

## Further Notes

- ADR 0033 is the governing decision for separating Service-owned Migration Definition from execution policy. This specification refines normal runtime policy to validate-only and restricts initialization to explicit disposable/reference execution.
- ADR 0004 and ADR 0031 remain governing decisions for protocol-neutral Service API and transport adaptation. Declarative HTTP annotations belong to the Outbound Adapter, not `CatalogApi`.
- ADR 0035 keeps concrete IAM ownership outside the scaffold. The Identity App remains executable reference evidence, which is why ephemeral keys are documented rather than promoted into a production key-management project.
- The Decision Ledger now treats reusable Cache policy as Deferred, Lock and Scheduler as Incubating/Frozen, and Object Storage as Withdrawn.
- The canonical Context no longer defines Upload Policy. Future storage terminology must be introduced only when a concrete business capability resolves its meaning.
- The selected test seams were already agreed during the preceding design review: Starter contracts, App startup/integration tests, the independent Notes consumer and both reference-product topologies. The `to-spec` invocation requests synthesis without another interview.
- The repository currently contains unrelated in-progress changes. Implementation must preserve them and limit edits to the surfaces defined by this specification.
