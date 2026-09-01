Status: ready-for-agent

# 按业务模块和 Adapter 方向重构业务服务

## Problem Statement

当前 Catalog、Order 和 Inventory 业务服务使用了不一致且不可扩展的 package 与 Spring 装配结构。部分代码按技术层组织，部分代码按调用方向组织：消息 Consumer 和 HTTP Controller 位于顶层 Inbound package，而消息 Publisher、HTTP Client、Persistence 和内存实现位于宽泛的 Infrastructure package。两者在同一级混用了“调用方向”和“实现性质”两套分类轴，使开发者无法仅凭位置判断一个类是驱动业务的 Inbound Adapter、实现业务 seam 的 Outbound Adapter，还是与业务 seam 无关的运行时机制。

服务级 `XxxApplicationConfiguration` 进一步假设一个业务服务只有一个业务模块。真实业务服务会包含多个具有独立模型、语言或变化原因的业务模块；如果继续使用服务级总配置，Application Service、Repository、Publisher 和协作者 wiring 会逐步集中到一个浅而宽的配置入口。反过来，如果为每个用例创建 Configuration 或 Handler，又会产生大量没有独立价值的接缝和样板代码。

消息入口当前也存在形状不一致。Inventory 使用位于 Inbound 的 Message Handler 处理反序列化并调用用例，Order 则在 Application 中暴露意图模糊的 `InventoryResultHandler`。Configuration 有时仅负责 Bean wiring，有时又直接包含消息消费 lambda、payload mapping 或迁移执行副作用。数据库迁移也分别由 Persistence 配置、叶子 App 配置和模块化单体配置触发，导致“服务拥有迁移内容”和“部署决定是否、何时执行迁移”无法独立变化。

需要一套能够扩展到多业务模块、保持 Domain/Application 协议中立、显式支持两个 App 拓扑并可由架构测试约束的统一规则。实现者必须能够从业务模块、Adapter 方向和公开装配入口判断代码归属，而不依赖历史惯例、Component Scan 或 Bean 优先级碰巧工作。

## Solution

业务服务改为“业务模块优先”的纵向组织。一个业务模块聚合共享模型、语言和变化原因的一组用例，并在模块内部包含 Application、Domain、Inbound Adapter 和 Outbound Adapter。每个业务模块在模块根部暴露一个小型 `XxxModuleConfiguration`，仅负责构造协议中立的 Application Service；它不是每个用例一份，也不激活 HTTP、Kafka、Persistence、Remote Client 或其他实现。

所有与外部交互的实现统一归入 Adapter。`adapter/inbound` 包含 HTTP、消息和调度等驱动业务用例的入口；`adapter/outbound` 包含 Persistence、Remote HTTP、消息发布、缓存和内存实现等满足业务 seam 的 Adapter。业务服务不再设置通用的顶层 `configuration` 或 `infrastructure` 杂物包。无法归入业务 seam、但由服务拥有的数据库迁移声明放在服务级 `migration` package；通用执行器、事务、可靠消息、安全与观测等平台机制继续由 Framework Foundation、Adapter 或 Starter 提供。

消息入口统一使用显式配置切片。公开的 `XxxInboundConfiguration` 提供 Spring Cloud Stream 所需的具名 Consumer Bean、Inbound Message Contract 和依赖 wiring；package-private `XxxMessageConsumer` 承担消息到达后的控制流；只有出现真实的 meaning、invariant、shape、serialization 或 versioning 差异时才保留独立 Mapper。Application 中不再使用 `*MessageHandler` 表达业务用例。Order 对库存结果的业务意图由 `OrderApplicationService.resolvePendingStock` 表达，使 `PENDING_STOCK` 根据 Inventory Reserved 或 Inventory Rejected 进入相应终态。

数据库迁移拆成声明与执行两部分。业务服务和平台能力分别发布无副作用的 Migration Definition，声明组件身份、资源位置和服务拥有的元数据；App Assembly 或部署选择参与的定义和执行策略；平台迁移运行时将定义与执行选项组合成可执行 Migration Plan。仅导入业务模块、Persistence Adapter 或 Migration Definition 不得自动修改数据库。Reference Apps 可以显式选择 startup 执行策略，生产部署可以选择外部 release step，而不复制业务迁移元数据。

App Assembly 继续作为显式 composition root：选择业务模块、Inbound Adapter、Outbound Adapter、Migration Definition、执行策略以及 Local/Remote 协作者。微服务 Golden Path 和业务核心模块化单体必须继续运行同一业务语义、消息契约、Outbox/Inbox 和公共 HTTP 验收，不通过本次重构改变业务状态、外部接口或支持矩阵。

## User Stories

1. As a 业务服务开发者, I want 按业务模块而不是全局技术层组织代码, so that 一项业务能力的模型、用例和 Adapter 可以在一个局部范围内理解和修改。
2. As a 业务服务开发者, I want 通过独立模型、语言和变化原因判断是否创建新业务模块, so that 每个实体或用例不会被机械拆成浅模块。
3. As a 业务服务开发者, I want 一个业务模块可以包含多个 Application Service 和用例, so that Configuration 粒度不等于用例数量。
4. As a Domain 开发者, I want Domain 代码留在所属业务模块内并保持协议中立, so that HTTP、Kafka、JSON 和 Spring 类型不会污染业务规则。
5. As an Application 开发者, I want Application Service 通过构造参数接收业务 seam, so that同一业务实现可以使用不同 Local、Remote、Persistence 或消息 Adapter。
6. As an Application 开发者, I want Application 中不出现 `*MessageHandler`, so that类名表达业务意图而不是入口协议。
7. As an Order 开发者, I want 库存结果入口调用明确的待库存状态消解动作, so that代码表达订单为何变化而不只是“处理一个结果”。
8. As an Order 开发者, I want Inventory Reserved 将等待库存订单推进为确认状态, so that现有业务状态机在重构后保持不变。
9. As an Order 开发者, I want Inventory Rejected 将等待库存订单推进为拒绝状态, so that现有库存不足行为保持不变。
10. As a 业务模块维护者, I want 每个业务模块只有一个小型公开 Module Configuration, so that多个 App 可以复用相同 Application wiring。
11. As a 业务模块维护者, I want Module Configuration 不自动激活任何 Transport 或 Infrastructure 实现, so that业务模块与部署选择可以独立组合。
12. As an App 装配维护者, I want 显式选择业务模块, so that运行时不会因为 Component Scan 偶然获得未声明能力。
13. As an App 装配维护者, I want 显式选择每个 Inbound Adapter, so that一个 App 只暴露其受支持的 HTTP、消息和调度入口。
14. As an App 装配维护者, I want 显式选择每个 Outbound Adapter, so that Local、Remote、Persistence、消息和内存实现不会同时激活或依赖 Bean 优先级。
15. As a Modular Monolith 维护者, I want 复用与独立服务相同的业务模块配置, so that组合拓扑不会复制 Application Service 的构造逻辑。
16. As a Microservice App 维护者, I want 继续使用 Remote Catalog Adapter, so that独立 Order App 保持真实进程边界。
17. As a Modular Monolith 维护者, I want 继续使用 Local Catalog 实现, so that同进程组合不需要绕行 HTTP。
18. As a Messaging 开发者, I want 消息 Consumer 与 Publisher 都位于 Adapter 体系内, so that调用方向成为一致且可导航的分类轴。
19. As a Messaging 开发者, I want 消费入口位于 `adapter/inbound/messaging`, so thatBroker 驱动业务的方向一目了然。
20. As a Messaging 开发者, I want 发布实现位于 `adapter/outbound/messaging`, so thatApplication 发起外部副作用时依赖的是业务拥有的 seam。
21. As a Messaging 开发者, I want 一个公开 Inbound Configuration 固定 Consumer Bean 名和消息契约, so thatSpring Cloud Stream binding 与业务消费实现保持显式连接。
22. As a Messaging 开发者, I want package-private Message Consumer 承担消费控制流, so thatConfiguration 不包含反序列化、分支和业务调用 lambda。
23. As a Messaging 开发者, I want 仅在存在可观察语义差异时创建 Mapper, so that不会为了目录对称产生字段一一复制的样板代码。
24. As a Web 开发者, I want Controller 和异常到 HTTP 状态的映射位于 `adapter/inbound/http`, so thatHTTP 语义由提供方业务模块拥有。
25. As a Remote Client 开发者, I want HTTP Client、凭据获取和超时 wiring 位于 `adapter/outbound/http`, so thatApplication 不依赖远程协议机制。
26. As a Persistence 开发者, I want Mapper 和 Repository 实现位于 `adapter/outbound/persistence`, so thatDomain Repository 接口与数据库实现保持明确 seam。
27. As a Test 开发者, I want 内存 Repository 和 Publisher 仍被识别为 Outbound Adapter, so that测试替代实现不会被误放入 Infrastructure 杂物包。
28. As a Repository 维护者, I want 业务服务中没有通用顶层 `configuration` package, so that技术配置不会再次集中成难以导航的总目录。
29. As a Repository 维护者, I want 业务服务中没有通用顶层 `infrastructure` package, so that无法分类的代码不会长期积累在宽泛容器中。
30. As a Migration 作者, I want 服务拥有迁移组件身份、资源和元数据, so that数据库演进仍由数据所有者控制。
31. As a Migration 作者, I want Migration Configuration 只发布无副作用的定义, so that仅导入配置不会立即修改数据库。
32. As an App 装配维护者, I want 选择哪些业务和平台 Migration Definition 参与运行, so that微服务与模块化单体可以组合正确且不重复的迁移集合。
33. As an Operations 维护者, I want 独立选择 startup 或外部 release-step 执行策略, so that生产运行时不必持有迁移权限。
34. As a Platform 开发者, I want 一个通用迁移运行时消费定义和执行选项, so that每个 App 不再复制 DatabaseMigrator lifecycle 代码。
35. As a Messaging 平台维护者, I want 平台消息 Schema 由平台拥有并在一个 App 中最多选择一次, so that模块化单体不会因多个业务模块重复执行消息迁移。
36. As a Database 维护者, I want PostgreSQL 和 MySQL 继续使用各自原生迁移资源, so that现有数据库支持矩阵不因 package 重构缩水。
37. As a QA 工程师, I want 架构测试验证业务模块优先和 Adapter 方向, so that后续提交不能重新引入层优先或 Infrastructure 杂物包。
38. As a QA 工程师, I want 配置切片测试证明业务模块、Inbound Adapter 和 Outbound Adapter 可以独立选择, so that显式装配不是仅存在于文档中的约定。
39. As a QA 工程师, I want 迁移测试证明定义本身无副作用且只有选定执行策略会运行, so that部署权限边界具有可执行证据。
40. As a QA 工程师, I want 同一套公共黑盒验收继续覆盖微服务和业务核心模块化单体, so that目录重构不会改变客户端可见行为。
41. As a Security 维护者, I want Tenant、Actor、Initiator 和 Correlation 继续由可信入口建立和传播, so that包移动不会绕过现有信任边界。
42. As an Operations 维护者, I want Outbox、Inbox、bounded retry 和 Dead Letter 行为保持不变, so thatAdapter 重组不会削弱消息可靠性。
43. As an AI 编码代理, I want 从业务模块和 Adapter 方向直接推断新代码位置, so that实现任务不需要重新解释每个服务的历史布局。
44. As a Human Reviewer, I want 通过稳定的 Module Configuration 和业务 seam 评审依赖方向, so that代码审查聚焦真实接口而不是 Spring 内部 wiring。
45. As a Downstream Consumer, I want 公共 HTTP、Service API 和消息契约保持兼容, so that内部 package 重构不要求客户端迁移。
46. As a Repository 维护者, I want 旧的 deprecated 服务聚合配置在兼容期内不再被新 App 使用, so that可以逐步迁移而不立即制造无关破坏。

## Implementation Decisions

- ADR 0032 是业务模块与 Adapter package 结构的主要依据；ADR 0033 是迁移声明与执行分离的主要依据。ADR 0006 除“先按层、再按业务能力”的 package 顺序外继续有效，ADR 0025、0026 和 0031 的提供方所有权、显式配置切片和按语义差异映射规则继续有效。
- Business Service 先按 cohesive business module 组织。业务模块以共享模型、统一语言和共同变化原因划分，不按单个 Handler、Controller、Entity、Repository 或用例拆分。
- 当前 Order 创建、查询以及根据库存预占结果消解 `PENDING_STOCK` 都围绕同一个 Order 聚合，保持在同一业务模块；本规格不为它们创建三个模块。
- 当前 Inventory 库存预占行为形成一个业务模块；Catalog 当前商品目录查询行为形成一个业务模块。未来业务模块名称必须来自项目统一语言，而不是 `common`、`core`、`impl` 或 `misc`。
- 每个业务模块在模块根暴露一个 public `XxxModuleConfiguration`。它只构造该模块的协议中立 Application Services 和必要的应用级装饰器，不创建 Controller、Consumer、HTTP Client、Repository 实现、Publisher 实现、Migration runner 或运行环境值。
- 一个业务模块可以拥有多个 Application Service。不得因为新增一个用例就自动新增 Module Configuration，也不得要求“一 Handler 一用例”。
- 当一组业务模块对所有受支持 App 都不可分割时，可以提供一个 service-wide configuration facade。该 facade 只能导入 Module Configuration，不得创建 Bean、选择 Adapter 或隐藏 Local/Remote、HTTP/Kafka、Persistence、Migration 等拓扑决策。
- 业务服务不再使用 generic top-level `configuration` 或 `infrastructure` package。Spring wiring 必须位于所装配业务模块或 Adapter 的公开配置切片中；无法归类的代码不能以 Infrastructure 名义落地。
- 每个业务模块使用 `adapter/inbound` 和 `adapter/outbound` 表达相对于业务核心的调用方向。Application 和 Domain 不得依赖任一 Adapter package。
- HTTP Controller、HTTP request/response mapping、HTTP error/status contribution 和入口 validation 位于对应业务模块的 Inbound HTTP Adapter。
- 消息入口位于对应业务模块的 Inbound Messaging Adapter。它由一个 public `XxxInboundConfiguration`、一个 package-private `XxxMessageConsumer` 和按真实语义差异可选的 package-private Mapper 构成。
- `XxxInboundConfiguration` 负责具名 Consumer Bean、Inbound Message Contract 和对象 wiring，不包含消息 payload 解析、结果类型分支或业务状态修改控制流。
- `XxxMessageConsumer` 接收 Transport Message，通过平台 Reliable Consumer 完成契约校验、可信 Execution Context、Inbox、事务和失败分类，并在回调中把经过验证的 Serialized Message 转换为协议中立输入后调用 Application Service。
- Application 中不使用 `*MessageHandler` 命名业务接口。不得为了消息入口的形式对称创建只有一个实现、只有一个方法的公共 Handler seam。
- Order 库存结果入口直接调用 `OrderApplicationService.resolvePendingStock`。该动作保持现有 `PENDING_STOCK` 到 `CONFIRMED` 或 `REJECTED` 的状态转换、重复结果语义、授权和 Persistence 行为。
- Outbound HTTP、messaging、persistence、cache 和 memory Adapter 位于拥有其业务 seam 的模块下。Adapter configuration 与实现共置，并由 App Assembly 显式选择。
- `ReserveInventoryPublisher`、Repository 和其他协议中立 seam 继续由 Application 或 Domain 拥有；Outbox、MyBatis、HTTP 和 in-memory 类型只作为对应 seam 的 Adapter。
- Mapper 创建规则遵循 ADR 0031：Transport mechanics 始终由 Adapter 拥有，但只有 meaning、invariant、shape、serialization 或 versioning 存在差异时才引入独立模型转换。
- App Assembly 是唯一 topology composition root。它显式导入业务 Module Configuration、Inbound Adapter Configuration、Outbound Adapter Configuration、Migration Definition 和 Local/Remote collaborator 选择。
- 微服务 Order App 继续选择 Remote Catalog Adapter；业务核心模块化单体继续选择 Local Catalog 实现。一个 Application Context 中每个必需业务 seam 必须恰好有一个实现。
- 现有 Catalog、Inventory deprecated aggregate configuration 可以作为兼容 shim 暂时保留原有公共身份，但新 App 不得导入。删除这些 shim 需要单独的破坏性版本决策。
- 业务服务在 service-level `migration` package 发布 side-effect-free Database Migration Definition。业务模块 package 和 Persistence Adapter Configuration 不执行迁移。
- Migration Definition 包含 Database Component identity、服务拥有的 locations 和 placeholders 等描述信息，但不包含 startup/external 选择、运行身份或其他环境执行策略。
- 当前执行对象中混合的 identity initialization 选项必须从服务定义中分离为 assembly/deployment-owned execution options。平台运行时根据 definition 与 options 构造 executable Migration Plan。
- 通用 migration executor 和 Spring Boot lifecycle integration 位于 Framework runtime integration，而不是复制到每个业务服务或 App。Foundation 保持迁移模型与执行能力，Starter 负责条件化启动集成。
- App Assembly 或部署配置选择 startup、external 或 disabled 行为。仅将 Migration Definition 放入 classpath 或导入其 Configuration 不得连接数据库或执行 SQL。
- Reliable messaging platform owns its messaging database Migration Definition. An App Assembly selects that platform definition once per database runtime even when multiple Business Services share the same process.
- Reference microservice Apps and business-core modular monolith must explicitly preserve their current startup migration behavior through the selected runtime policy until deployment packaging supplies an external migration step.
- PostgreSQL and MySQL service-owned migration resources, logical component identities and independent Flyway histories remain unchanged unless a separate database migration requires a compatible forward change.
- No public HTTP route, Service API contract, Message Type, Destination, payload schema, database table, Order state, authorization rule, Tenant rule, Outbox/Inbox guarantee or supported topology changes are part of this refactor.
- Existing Maven artifact boundaries remain. Do not create one artifact per Adapter or business module while only one implementation and one deployable Business Service exist.
- Architecture documentation and executable Architecture Testkit rules must be updated together so accepted package rules and code cannot drift.
- The implementation must preserve unrelated user changes in the current dirty worktree and avoid formatting or moving files outside the affected business-service, migration-runtime, App-composition and architecture-test surfaces.

## Testing Decisions

- A good test observes behavior through a business module interface, selected Adapter interface, App context, migration runtime, or public black-box contract. It does not assert private helper calls, Configuration method invocation counts, Mapper existence, constructor wiring order or file movement as behavior.
- Two highest seams are required because external behavior alone cannot prove structural ownership: the existing repository Architecture Testkit verifies module/package/dependency rules, and the existing shared public black-box acceptance harness verifies that both supported application topologies retain identical behavior.
- Architecture tests must require Business Service production classes to belong to a named business module, service-level migration package, explicitly allowed compatibility facade, or another intentionally enumerated service surface.
- Architecture tests must require HTTP and message-driving framework dependencies in `adapter/inbound`, and concrete HTTP client, messaging publication, Persistence and replaceable memory implementations in `adapter/outbound`.
- Architecture tests must continue proving that Domain/Application do not depend on Spring Web, Spring Messaging, Spring Cloud Stream, Kafka, JSON serialization or concrete Adapter implementations.
- Architecture tests must reject business Controller, Consumer, payload Mapper and business message-processing functions in App Assemblies.
- Architecture tests must reject new generic top-level Business Service `configuration` and `infrastructure` packages while allowing the temporary deprecated compatibility facades explicitly documented by this spec.
- Architecture tests must verify that Module Configuration does not depend on Inbound or Outbound Adapter implementations and does not expose Consumer-returning methods.
- Existing configuration-slice tests are prior art. Each current business module receives a focused context test proving its Module Configuration can construct Application Services when protocol-neutral seam test doubles are supplied, without activating HTTP, messaging, Persistence or migration execution.
- Inbound Adapter slice tests must register the Application behavior and generic platform dependencies needed by that Adapter, activate only its public Inbound Configuration and observe the resulting Consumer behavior without importing the complete App.
- Messaging Adapter tests should submit Transport Messages through the Consumer Bean and observe business outcomes using actual Application Services with in-memory Outbound Adapters where practical. They must not add a public one-method Handler solely to simplify mocking.
- Order messaging tests must prove that valid Inventory Reserved and Inventory Rejected inputs invoke the pending-stock resolution behavior and preserve existing idempotent or conflicting-terminal-result semantics.
- Inventory messaging tests must preserve malformed-payload non-retryable classification and valid Reserve Inventory Command processing. Generic Inbox, retry and trusted-context mechanics remain covered by the platform Reliable Consumer tests rather than duplicated in each business module.
- Mapper tests remain only where a real Mapper exists. If a Mapper is removed because contract and Application semantics are identical, its field-copy tests are removed rather than replaced with tests of implementation trivia.
- Outbound Adapter contract tests must continue covering MyBatis Repository behavior, Outbox publication and Remote HTTP behavior through their existing business seams. Moving packages must not create duplicate test suites.
- App context integration tests must prove each required seam has exactly one selected implementation, the microservice topology selects Remote collaborators, the modular-monolith topology selects Local collaborators, and unselected Adapters are absent.
- Migration Definition unit tests must prove definitions expose the expected component identity and supported resource set without obtaining a DataSource or executing SQL.
- Migration runtime tests must prove that importing definitions without an enabled runner has no side effect, startup mode executes only selected definitions, disabled/external mode does not execute, and shared platform definitions execute once.
- Existing Database Migrator Testcontainers coverage is prior art for identity enforcement, Flyway history isolation, PostgreSQL/MySQL execution and validation. Extend that seam rather than duplicating low-level Flyway assertions in each Business Service.
- Migration integration tests must cover a multi-module App selection containing Catalog, Order, Inventory and reliable messaging definitions, including deterministic execution and no duplicate messaging migration.
- The existing Order, Inventory and modular-monolith App integration tests remain prior art for Spring context and real Kafka behavior. Update imports and assertions to the new module/Adapter configuration slices without weakening behavior.
- The shared reference-product acceptance harness remains the product seam. It must pass unchanged for the five-App microservice topology and the business-core modular-monolith topology, covering public HTTP, asynchronous inventory resolution, Tenant isolation and eventual consistency.
- Verification runs the narrowest affected module and architecture tests first, then affected App integration tests, then full reactor verification. The shared black-box harness runs for both supported topologies before the refactor is considered complete.
- Successful compilation, package moves alone, `contextLoads`, direct calls to private configuration methods or one passing topology are insufficient completion evidence.

## Out of Scope

- Splitting one Business Service into multiple deployable microservices or creating new bounded contexts.
- Creating hypothetical Returns, Fulfillment, Pricing or other business modules that do not yet have real models and requirements.
- Creating one Maven artifact per business module, HTTP Adapter, messaging Adapter or Persistence Adapter.
- Renaming existing public Maven artifacts or changing their published dependency relationships beyond what compilation requires for the refactor.
- Removing the synchronous-shaped Inventory Java interface; that decision and implementation belong to the existing asynchronous Inventory contract-seam specification.
- Changing Inventory command/result Message Types, Destinations, payload fields or versioning.
- Changing public HTTP paths, request/response contracts, RFC 9457 errors or Gateway prefix behavior.
- Changing Order, Inventory or Catalog business rules, aggregate invariants, authorization permissions or Tenant semantics.
- Replacing Kafka, Spring Cloud Stream, Outbox, Inbox, retry classification or Dead Letter behavior.
- Introducing Local Message Transport or bypassing Kafka in the business-core modular monolith.
- Changing database schemas, table names, historical migrations, supported database versions or the PostgreSQL/MySQL support matrix except for framework metadata required to represent Migration Definitions.
- Designing production release tooling, CI/CD orchestration, Kubernetes Jobs, Helm hooks or database credentials; this spec only establishes the definition/execution seam they consume.
- Removing deprecated service configuration facades before an explicitly approved breaking release.
- Enforcing Mapper necessity through heuristic field-count architecture rules; semantic-difference review remains governed by ADR 0031 and behavior tests.
- Refactoring Identity or Gateway internal package layouts unless required to consume the generic migration runtime without duplicating migration definitions.

## Further Notes

- Current code demonstrates every inconsistency addressed by this specification: Catalog keeps several Spring configurations at its service root, Order and Inventory use a generic configuration package for some wiring, Inbound Adapter packages sit beside a broad Infrastructure package, publisher beans are mixed into Persistence configuration, and migration execution is spread across Persistence and App configuration.
- Current Order inventory-result handling uses an Application-level Handler name even though the accepted business intent is to resolve an Order waiting for stock. Current Inventory messaging uses a separate transport Handler. The target shape removes this naming inconsistency without forcing the two call directions into identical Application interfaces.
- Directory symmetry does not imply dependency symmetry. Inbound Adapters drive Application behavior; Outbound Adapters satisfy seams owned by Application or Domain. Do not create an inbound port solely because an outbound port exists.
- The business-module-first rule maximizes locality: a maintainer changing one cohesive business capability should navigate within one module. The Module Configuration is the small public composition interface; Application, Domain and Adapter details remain its implementation.
- ADR 0032 replaces only the layer-first package-ordering sentence in ADR 0006. The simplified DDD decision, explicit Application Services, Domain Aggregate Repositories, Application Query Repositories and rejection of automatic one-Handler-per-use-case patterns remain active.
- ADR 0033 closes the migration execution question that ADR 0010 intentionally left open. Service ownership of logical databases, native SQL, Expand/Contract and independent Flyway histories remains active.
- The existing App Assembly topology specification remains authoritative for provider-owned Inbound Adapters, dual-topology composition and Gateway responsibilities. This specification refines package and wiring shape without reopening those topology decisions.
- The existing Inventory asynchronous contract-seam specification remains authoritative for whether `InventoryApi` survives. Implementations should coordinate sequencing so package moves do not duplicate work, but neither specification broadens the other's scope.
- Recommended implementation order is: introduce and test the migration definition/runtime seam; refactor one reference business module end-to-end as the pattern; migrate the other two services; update App Assembly imports; tighten architecture rules; then run focused integration and both shared black-box topologies.
- The current worktree contains unrelated and in-progress changes. An implementation agent must inspect current state, preserve those changes and treat this spec as a target architecture rather than assuming the repository matches the last committed baseline.
