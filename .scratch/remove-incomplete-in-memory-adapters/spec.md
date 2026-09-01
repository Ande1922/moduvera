Status: ready-for-agent

# 移除不完整的 InMemory Adapter 并以真实运行时实现验证基础设施语义

## Problem Statement

当前仓库在正式生产源码和发布模块中提供了七个 `InMemory` 实现，但实际 App 装配没有选择它们，所有已知调用者都来自测试。这些实现因此不是受支持的运行时 Adapter，而是在生产 artifact 中发布的测试替身。

这些替身实现了与 JDBC、MyBatis 或 Outbox/Inbox 运行时 Adapter 相同的 Java 接口，却没有提供相同的事务、持久性、对象生命周期、数据库时间、并发、锁、乐观版本、租户隔离和故障恢复语义。例如，内存 Durable Publication 不要求活动的可写事务；内存 Order Repository 保存同一个可变对象且不推进持久化版本；内存 Inventory Store 复制库存判断和幂等算法；内存 Outbox 使用进程内状态和 JVM Clock 代替数据库竞争与时间。测试通过这些实现时容易形成“运行时契约已经被证明”的错觉，并可能掩盖只会在真实 Adapter 中发生的缺陷。

仓库已经拥有 PostgreSQL、MySQL、Kafka 和 App 层面的真实基础设施测试。继续维护不完整的内存实现会形成第二套行为、增加漂移面，并把测试便利错误地呈现为脚手架产品能力。需要删除这些正式实现，把基础设施语义的证据统一放到生产 Adapter 与真实组件上，同时保留测试文件内部用于隔离 Application 编排的窄 mock、stub 和 recording spy。

## Solution

从正式源码和发布模块中删除当前七个 `InMemory` Adapter，不把它们迁移到 `moduvera-test-support`，也不创建新的共享 fake artifact。Persistence、Transaction Boundary、Tenant Context 隔离、并发、Outbox/Inbox、Durable Publication 和 Repository 重建语义通过现有生产 Adapter 连接 Testcontainers 中的 PostgreSQL、MySQL 或其他所需真实组件来验证。

保留分层测试。Domain 与 Application 的纯规则或编排可以继续使用仅存在于测试源码中的 mock、脚本化 stub、lambda 或 recording spy，只要替身只提供当前测试需要的输入或记录调用，不模拟完整数据库、Broker 或持久消息状态机，也不声称与运行时 Adapter 可替换。涉及基础设施契约的断言必须跨越现有最高有效 seam，优先使用 Adapter 接口、App integration 或公共黑盒验收，而不是检查实现内部细节。

此次修改不新增通用 Adapter 父类型、不统一所有 seam 的封装方式、不拆分新的 Maven artifact，也不处理异步 Inventory Service API。它只收紧生产模块与测试替身的界限，并让现有支持声明继续由真实基础设施证据支撑。

## User Stories

1. As a 脚手架使用者, I want 发布的模块只包含具有真实运行时用途的 Adapter, so that artifact 内容不会暗示不存在的支持能力。
2. As a 脚手架使用者, I want 基础设施支持声明由真实组件测试证明, so that 测试通过代表我实际部署的组合能够工作。
3. As a 下游消费者, I want 不完整的内存实现从公共 classpath 消失, so that 我不会误把测试替身选择为生产配置。
4. As a 下游消费者, I want 未来出现内存运行时 Adapter 时有明确资格门槛, so that `InMemory` 名称对应受支持的真实用途而不是方便测试的简化实现。
5. As a Framework 维护者, I want Durable Publication 测试使用真实事务和 JDBC Outbox, so that `append` 的成功语义包含活动、可写且绑定正确 DataSource 的事务约束。
6. As a Framework 维护者, I want Outbox claim、lease、fencing、retry、terminal、redrive 和 cleanup 通过真实数据库验证, so that 进程内 Map 不会掩盖数据库时间和并发竞争差异。
7. As a Framework 维护者, I want Inbox 去重与业务变更通过真实事务验证, so that 重复投递安全包含原子提交和回滚语义。
8. As a Messaging 维护者, I want Outbox Worker 与 Relay 使用生产 Outbox Store 参加关键行为测试, so that 状态机与实际存储实现之间不会存在未验证的间隙。
9. As a Messaging 维护者, I want 传输失败测试可以使用测试内的脚本化 Message Transport, so that 我能精确控制 ACK、失败和未知结果而不发布一个假 Broker Adapter。
10. As a Catalog 开发者, I want Product Repository 的 Tenant Context、保存和重建行为由 MyBatis Adapter 与真实数据库验证, so that 进程内 Map 不会成为租户隔离证据。
11. As an Order 开发者, I want Order Repository 的插入、重建、状态更新和乐观版本行为由真实数据库验证, so that 保存同一可变对象不会掩盖缺失的持久化调用。
12. As an Inventory 开发者, I want 库存锁定、全成或全拒、幂等和并发行为由生产 Adapter 验证, so that 第二套内存库存算法不会与运行时实现漂移。
13. As an Application 开发者, I want 测试文件内仍可使用 mock 或 stub 提供预定结果, so that 纯编排测试保持快速且易于定位失败。
14. As an Application 开发者, I want 使用 recording spy 验证一次 Publisher 调用, so that 不需要为记录一个 Command 发布正式的 `InMemory` 类。
15. As an Application 开发者, I want 测试替身只表达当前场景所需行为, so that 它不会被误认为完整 Repository、Outbox 或 Broker 实现。
16. As a Domain 开发者, I want 纯业务不变量继续通过快速测试验证, so that 删除基础设施 fake 不会迫使所有纯计算测试启动容器。
17. As a Test 维护者, I want 清楚区分 unit test 与 integration test, so that 测试名称、运行成本和失败含义保持准确。
18. As a Test 维护者, I want 使用 `*IT` 承载需要 Testcontainers 的测试, so that Maven Surefire/Failsafe 生命周期与仓库约定一致。
19. As a Test 维护者, I want 一个容器生命周期可以服务同模块的相关测试, so that 提高语义真实性不会无谓放大构建时间。
20. As a Test 维护者, I want 测试通过公开 seam 观察结果, so that Adapter 内部重构不要求重写行为断言。
21. As a Test 维护者, I want 失败测试输出数据库、消息标识、租户和状态证据, so that 真实基础设施失败仍然可以快速诊断。
22. As a Security 维护者, I want Tenant Context 缺失时的 fail-closed 行为通过生产 Persistence 和 Inbox/Outbox Adapter 验证, so that 测试替身不会意外放宽隔离边界。
23. As a Reliability 维护者, I want 数据库提交、回滚和 Broker ACK 边界继续由真实集成测试覆盖, so that 删除内存实现不会降低可靠发布证据。
24. As a PostgreSQL 支持维护者, I want Golden Path 的 Repository 与消息存储契约继续在 PostgreSQL 上执行, so that 默认支持状态保持可验证。
25. As a MySQL 支持维护者, I want 兼容性范围继续运行聚焦的相同语义证据, so that 删除 fake 不会把数据库差异隐藏到共享 Java 实现后面。
26. As an App Assembly 维护者, I want 运行时 Bean 选择保持不变, so that 此次测试重构不会改变受支持应用拓扑。
27. As a QA 工程师, I want 关键订单履约链继续通过真实 App 和公共黑盒测试, so that 删除 unit fake 后最终用户可见行为仍有端到端证据。
28. As an Architecture 维护者, I want production source 与 test-only substitute 的责任清晰, so that 测试便利不会反向扩大产品表面。
29. As an Architecture 维护者, I want 不通过类名禁掉一切未来 `InMemory` 实现, so that 真正的受支持运行时需求仍可在通过完整契约后加入。
30. As an AI 编码代理, I want 仓库规则明确禁止发布不完整的测试 Adapter, so that 后续生成测试时优先选择 Testcontainers 或测试内窄替身。
31. As a Human Reviewer, I want 新增生产 Adapter 时看到真实消费者和契约证据, so that “以后可能有用”不足以扩张模块表面。
32. As a Human Reviewer, I want 测试内 mock 的作用域和断言目的明确, so that mock 不会逐渐演化成隐藏的第二套业务实现。
33. As a Maintainer, I want 删除当前未被 App 装配使用的正式内存类, so that 代码导航只呈现真实运行时选择。
34. As a Maintainer, I want 复用现有 PostgreSQL/MySQL messaging IT、Repository IT 和 App IT, so that 不创建重复的验证框架。
35. As a Maintainer, I want 此次工作保持在测试真实性范围内, so that Adapter 统一封装、业务模块泛化和异步 API 重构可以独立决策。

## Implementation Decisions

- Remove the seven current production-source test substitutes: `InMemoryDurablePublication`, `InMemoryOutboxStore`, `InMemoryInboxRepository`, `InMemoryProductRepository`, `InMemoryOrderRepository`, `InMemoryReserveInventoryPublisher`, and `InMemoryInventoryStore`.
- Do not move those implementations into `moduvera-test-support`, a test-fixtures Jar, or another published module. Their deletion reduces the public artifact surface rather than relocating it.
- Do not add replacement stateful fakes that implement complete Repository, Inventory Store, Durable Publication, Outbox Store, Outbox Administration, or Inbox Repository interfaces without full runtime semantics.
- A future in-memory runtime Adapter is not categorically forbidden. It requires a real supported runtime consumer and must pass the same applicable contract evidence as other runtime Adapters before entering production source.
- Keep local mocks, scripted stubs, lambdas, and recording spies inside test source. Prefer the narrowest substitute that controls one collaborator result or records one observable interaction.
- A local test double must not be reused as evidence for persistence, transaction, tenant isolation, concurrency, ordering, lease, retry, redrive, cleanup, deduplication, Broker acknowledgement, or crash-recovery semantics.
- Replace business Application tests that currently depend on stateful memory Repositories or Stores with one of two shapes: a focused Application orchestration test using narrow local doubles, or an integration test using the production MyBatis Adapter and Testcontainers when the assertion depends on stored state.
- Replace the recording in-memory Reserve Inventory Publisher with a test-local recording spy or lambda; publishing one Command for assertion does not justify a production Adapter.
- Move or rewrite message-core tests whose assertions require stored Outbox/Inbox state so they execute in the module that owns the JDBC runtime implementation. The foundation module must not acquire a reverse dependency on its Spring/JDBC starter.
- Keep pure transport-neutral value, validation, scheduling, and worker-decision tests in the foundation module when they can be expressed with immutable inputs or narrow scripted collaborators without emulating a database.
- Exercise `JdbcDurablePublication`, `JdbcOutboxStore`, and `JdbcInboxRepository` together with real transaction management and the existing database migrations for all persistence-dependent reliable-messaging behavior.
- Exercise Catalog, Order, and Inventory persistence behavior through their existing Repository or Store seams and production MyBatis implementations. Do not introduce a generic shared Repository abstraction as part of this work.
- Preserve the current PostgreSQL Golden Path and focused MySQL compatibility scope. This work changes evidence placement, not the Support Matrix.
- Preserve existing App Assembly wiring. No production configuration should begin selecting a new Adapter, and no Local/Remote topology choice changes.
- Preserve public HTTP contracts, message contracts, Destination values, database schemas, business state transitions, Transaction Boundary semantics, and Broker behavior.
- Do not create new Maven modules or split JDBC/Kafka responsibilities in this specification. Adapter packaging and generalized seam unification were explicitly deferred.
- Update repository testing guidance so future contributors distinguish test-local mocks from published runtime Adapters and require real-infrastructure evidence for infrastructure semantics.
- Treat ADR 0034 as the accepted rationale for this change. ADR 0012 continues to govern layered testing; this specification implements the refinement without eliminating fast Domain/Application tests.
- Avoid a brittle architecture rule that rejects every class named `InMemory`; such a rule could block a future complete runtime Adapter. Prefer module/source-set isolation, product-surface evidence, review guidance, and tests that prove runtime semantics.

## Testing Decisions

- A good test observes behavior through the highest existing seam that can prove the claim. It asserts returned business results, persisted/reloaded state, transaction outcome, message lifecycle, tenant isolation, or public App behavior rather than private fields, Map contents, Mapper call counts, or implementation class internals.
- Pure Domain rules remain fast unit tests without Spring or Testcontainers. Application orchestration remains eligible for fast unit tests using test-local mocks, scripted stubs, lambdas, or recording spies.
- Any test claiming Repository persistence, aggregate rehydration, audit metadata, optimistic versioning, Tenant Context isolation, rollback, row locking, or concurrent mutation must use the production MyBatis Adapter with a real PostgreSQL or MySQL Testcontainer.
- Any test claiming Durable Publication enlistment, Outbox lifecycle, database-time eligibility, atomic claim, fencing, lease takeover, retry, terminal state, redrive, bounded cleanup, Inbox deduplication, or transaction rollback must use the production JDBC messaging Adapter with a real database Testcontainer.
- Message Transport can be a test-local scripted mock when the test controls send success, retryable failure, non-retryable failure, interruption, or unknown ACK outcome. Real Kafka App integration and black-box tests remain responsible for Broker mapping, delivery and recovery claims.
- Catalog Application tests may use a stub Product Repository to verify authorization and not-found/result mapping. Tenant-isolated persistence assertions belong to Repository integration tests.
- Order Application tests may use a recording Order Repository and recording Publisher only for one-call orchestration assertions. Tests that create, reload, mutate, save, and observe optimistic version changes must use the production Repository and real transaction boundary.
- Inventory Application tests may script an Inventory Store decision to verify authorization and publish-only-when-created behavior. Atomic stock reservation, repeated-command idempotency, concurrent access and stored result reconstruction belong to production Store integration tests.
- Inbox Template and reliable-consumer tests that claim atomic deduplication with business mutation must use the JDBC Inbox Repository and a real transaction. Pure contract-rejection and handler-classification tests may use narrow local doubles.
- Outbox Worker and Relay tests should use the JDBC Outbox Store whenever their assertion depends on claimable state or persisted lifecycle. Transport observation may remain a local spy because it does not stand in for Outbox persistence.
- Reuse the existing PostgreSQL/MySQL JDBC messaging integration tests as prior art for Outbox/Inbox lifecycle, transaction, concurrency, index and dialect behavior.
- Reuse the existing focused business Repository integration tests as prior art for Catalog, Order and Inventory tenant isolation, rehydration, rollback, idempotency and concurrency behavior.
- Reuse Order, Inventory and modular-monolith App integration tests plus the topology-parameterized public acceptance harness as the highest seams for complete business and reliable-messaging behavior.
- Container-backed tests follow the repository naming convention `*IT` and run under Failsafe. Fast tests retain `*Test` and run under Surefire.
- Prefer shared container lifecycle within a test class or module and deterministic cleanup between cases. Do not trade correctness for a second hand-written persistence implementation merely to reduce startup time.
- Failure diagnostics should identify the tested Adapter, database dialect, tenant, relevant business/message identity, expected lifecycle state and observed outcome while avoiding credentials.
- Run the narrowest affected module tests first, then the messaging starter and affected business integration tests, followed by architecture/static checks and the full reactor according to repository policy.
- Completion requires current passing evidence for every migrated behavior. Deleting the classes and disabling their tests, reducing assertions, or relying only on compilation does not satisfy the specification.

## Out of Scope

- Creating a common parent interface, generic Adapter registry, base Repository, or uniform wrapper for all seams.
- Standardizing every Business Service implementation or extracting business Repositories into a reusable framework.
- Creating one Maven artifact per Adapter or reorganizing the JDBC messaging implementation independently from the Kafka starter.
- Removing mocks, stubs, spies, lambdas, or other narrow test doubles from test source.
- Prohibiting a future complete and supported in-memory runtime Adapter that passes the applicable contract suite.
- Refactoring the asynchronous Inventory Service API or changing its message Inbound Adapter; that work is owned by a separate specification/session.
- Changing Catalog Local/Remote Service API selection, Order public API, Inventory message contracts, or any App topology.
- Changing database schemas, migration definitions, supported PostgreSQL/MySQL versions, Transaction Boundary behavior, Outbox/Inbox algorithms, retry policy, Dead Letter behavior, Kafka bindings or Destination values.
- Adding a new testing framework, Docker replacement, embedded database, H2 compatibility layer, local Broker implementation, or production deployment mechanism.
- Expanding the supported infrastructure matrix or promoting incubating Lock, Scheduler, Object Storage, cache or AI capabilities.

## Further Notes

- ADR 0034 records the accepted decision: production and published modules do not provide reduced-fidelity `InMemory` test substitutes; infrastructure semantics are verified through runtime Adapters and real components, while test-local mocks/stubs/spies remain allowed for Domain/Application orchestration.
- ADR 0012 remains applicable. “Layered testing” does not require a stateful fake for every seam: fast tests cover pure rules and orchestration, real-component integration tests cover infrastructure, and black-box acceptance covers critical cross-service behavior.
- ADR 0032's general placement language does not authorize test-only in-memory substitutes under production `adapter/outbound` packages. Only an independently justified runtime Adapter belongs there.
- Current code evidence found all seven named `InMemory` implementations in production source and all known usages in tests; no App Assembly selects them as runtime Beans.
- The user explicitly confirmed that mocks inside test files are acceptable and that the prohibited shape is an incomplete `InMemory` implementation supplied by a formal production or published module.
- The working tree already contains unrelated restructuring and documentation changes. Implementation must preserve those changes and coordinate with the separate Inventory API work rather than overwriting it.
