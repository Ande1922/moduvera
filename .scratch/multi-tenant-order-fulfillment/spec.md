Status: ready-for-agent

# 完成多租户订单履约黄金路径

## 阅读说明 — 2026-09-06

本文件保留原始立项背景和批准范围。下文 Problem Statement 与 Further Notes 中的
“当前”“尚缺”描述立项时的差距；当前能力以
[Product Surface](../../docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md) 为准。

本次状态核对：01–15 号 issue 已 resolved，剩余工作是
[16 — Outbox 性能基线](issues/16-baseline-outbox-performance.md)。黄金路径已交付不代表
性能基线已完成，因此父规格继续保持 ready-for-agent。继续工作时从该剩余票开始，
需要追溯既有契约或验收时再读取已完成票据。

## Problem Statement

当前脚手架已经形成了一组明确的架构决策，也已有 BOM、`platform-kernel`、Web、OAuth2 Resource Server、MyBatis-Plus Data Starter、数据库迁移组件、消息可靠性契约以及 Catalog、Order、Inventory 的领域骨架。但是，现有可运行证据主要仍是简单 Notes Demo、内存适配器和模块级测试，无法回答使用者最关心的问题：这套脚手架能否承载一个真实的微服务业务闭环，是否真的好用，以及已经决定的租户、安全、远程调用、事务、消息、持久化和故障恢复能力能否协同工作。

缺少完整成品还造成两个直接问题。首先，平台开发者无法区分哪些抽象具有真实复用价值，哪些只是提前设计的 Interface 或 Maven 模块。其次，业务开发者和 AI 都必须在分散文档与样例之间推断正确用法，容易把候选能力当成已支持能力，或在业务代码中泄漏 Tenant、MyBatis-Plus、Kafka、JWT、Flyway 和 Outbox/Inbox 的实现细节。

需要交付一个比 CRUD Demo 更复杂、但业务边界仍足够清晰的多租户 B2B 订单履约验证业务。它必须以多进程微服务拓扑运行，通过真实 HTTP、PostgreSQL 和 Kafka 完成订单创建、库存预留、最终状态查询、幂等消费和故障恢复。该成品既是脚手架的黄金路径，也是客观检验现有抽象是否过度设计的基准。

## Solution

实现一个完整的多租户订单履约参考应用，由 Gateway、Identity、Catalog、Order 和 Inventory 五个 App 装配组成。租户用户通过 Gateway 登录并获得 opaque browser token；Gateway 将外部身份交换为短生命周期 internal JWT，再访问 Order 公共 API。Order 通过真实远程 HTTP 调用 Catalog 获取权威产品与价格快照，在一个显式 `TransactionBoundary` 中同时持久化 `PENDING_STOCK` 订单和 `ReserveInventoryCommand` Outbox 记录。Outbox relay 在事务提交后通过 Spring Cloud Stream 的 imperative functional model 和 Kafka Binder 发布命令。

Inventory 在可信消息入口建立租户上下文，通过 Inbox 去重，在自己的 PostgreSQL 数据库中原子预留库存，并通过 Outbox 发布 `InventoryReserved` 或 `InventoryRejected`。Order 幂等消费结果并将订单变为 `CONFIRMED` 或 `REJECTED`。用户只通过公共 HTTP 查询最终状态；黑盒验收同时证明跨租户不可见、重复消息不产生重复副作用、并发预留不超卖，以及“数据库已提交但 Kafka 尚未发送”时在进程或 Broker 恢复后可以继续完成。

业务代码只接触业务拥有的 Service API、Command、Query、Event、领域 Repository、`TransactionBoundary` 和 `UseCaseAuthorizer`。TenantLine、Kafka Binder、JWT claim、Flyway history、Outbox lease、重试与 correlation 传播均留在系统入口、Starter、App 装配或基础设施适配器中。PostgreSQL/MyBatis-Plus 是完整黄金路径；MySQL 通过同一 Repository 与迁移 TCK 保持长期兼容，不建立第二套端到端矩阵。Local implementation 保留为可选能力，但不阻塞本规格，也不复制整套验收。

## User Stories

1. As a 租户用户, I want 通过公共 API 登录并获得 opaque browser token, so that 浏览器凭证不直接暴露内部服务使用的 JWT。
2. As a 租户用户, I want 使用同一外部 token 通过 Gateway 创建订单, so that 我不需要理解内部服务身份交换。
3. As a 租户用户, I want 提交产品和数量而不提交价格, so that Order 使用 Catalog 的权威价格而不是信任客户端数据。
4. As a 租户用户, I want 创建订单后立即得到订单标识、`PENDING_STOCK` 状态和查询地址, so that 我可以跟踪异步履约进度。
5. As a 租户用户, I want 通过公共 Order API 查询订单, so that 我不需要直接访问 Catalog、Inventory 或 Kafka。
6. As a 租户用户, I want 在库存充足时最终看到 `CONFIRMED`, so that 我能确认库存已经原子预留。
7. As a 租户用户, I want 在任意一个订单行库存不足时最终看到 `REJECTED`, so that 我不会得到部分成功但语义不明的订单。
8. As a 租户用户, I want 订单保留创建时的产品名称、单价和币种快照, so that Catalog 后续变化不会改写历史订单含义。
9. As a 租户用户, I want 成功响应保持原生 JSON 而不套通用响应壳, so that HTTP 客户端可以直接使用业务契约。
10. As a 租户用户, I want 校验、未认证、无权限、未找到和业务冲突使用真实 HTTP 状态码与 RFC 9457 Problem Details, so that 错误处理可预测且不泄漏内部实现。
11. As a 租户用户, I want 每次请求都能获得或透传 correlation ID, so that 一次订单履约可以跨 HTTP 和消息链路定位。
12. As a tenant-a 用户, I want 无法读取 tenant-b 的订单，即使我知道订单标识, so that 资源存在性和数据都不会跨租户泄漏。
13. As a tenant-a 用户, I want 无法通过提交 Tenant ID 冒充 tenant-b, so that 租户只来自可信 Execution Context。
14. As a 无权限用户, I want 创建订单请求被明确拒绝, so that `order:create` 权限在 Application Service 入口被执行。
15. As a 只有读权限的用户, I want 可以查询自己的订单但不能创建订单, so that tenant-level RBAC 能表达不同职责。
16. As a Gateway, I want 将 opaque browser token 一次性交换为短生命周期 internal JWT, so that 下游资源服务只验证统一的内部凭证。
17. As a Gateway, I want 只公开公共 API 而不公开内部 Catalog 路由和 Identity exchange 路由, so that 内部边界不会因路由方便而意外暴露。
18. As an Identity App 装配维护者, I want 使用正常登录与 token exchange 契约提供演示身份, so that 验收不依赖生产代码中的测试专用认证端点。
19. As a Resource Service, I want 验证 issuer、audience、签名、期限和 actor 类型, so that internal JWT 不是仅解析不验证的字符串。
20. As a Resource Service, I want 区分 USER、SERVICE 与 original initiator, so that 服务间调用和消息仍能保留真实发起者。
21. As an Order 开发者, I want 只依赖 `CatalogApi` 获取产品快照, so that 业务逻辑不依赖 HTTP 客户端、序列化或进程拓扑。
22. As an Order 开发者, I want 远程 Catalog client 实现同一个 Service API, so that 业务服务不编码远程调用细节。
23. As an Order 开发者, I want 在进入本地事务前完成 Catalog 远程调用, so that `TransactionBoundary` 内不会持有数据库事务等待网络。
24. As an Order 开发者, I want 在一个显式本地事务中保存订单和 Outbox 记录, so that 不会出现订单已保存但命令永久丢失。
25. As an Order 开发者, I want Repository 方法不接收 Tenant ID, so that 技术租户隔离不会污染日常业务接口。
26. As an Order 开发者, I want 业务 Domain 不依赖 MyBatis-Plus annotation、Wrapper 或数据库 Row, so that领域模型可以快速单测并保持框架无关。
27. As an Order 开发者, I want `ReserveInventoryPublisher` 保持业务意图明确, so that Application Service 不依赖通用 Message Bus 或 `StreamBridge`。
28. As an Inventory 开发者, I want `ReserveInventoryCommand` 由 Inventory Service API 拥有, so that命令契约随接收方业务规则演进。
29. As an Inventory 开发者, I want 重复的 command ID 返回既有结果而不再次扣减库存, so that at-least-once delivery 不会造成重复副作用。
30. As an Inventory 开发者, I want 多行库存预留全部成功或全部回滚, so that Order 不会进入平台未定义的部分预留状态。
31. As an Inventory 开发者, I want 并发订单竞争最后一份库存时最多一个成功, so that 系统不会超卖。
32. As an Inventory 开发者, I want 按稳定产品顺序锁定或更新库存行, so that 多行订单在并发下尽量避免死锁并保持确定性。
33. As an Inventory 开发者, I want 用 `InventoryReserved` 或 `InventoryRejected` 表达已发生的集成事实, so that Order 不需要读取 Inventory 数据库。
34. As an Order 开发者, I want 重复的库存结果消息成为无副作用 no-op, so that Kafka 重投不会重复修改订单。
35. As an Order 开发者, I want 冲突或不匹配的库存结果不覆盖合法状态, so that 异常消息会被隔离而不是静默破坏订单。
36. As a Messaging 组件使用者, I want 通过一个 Kafka Starter 获得 Binder、消息信封、Outbox relay、Inbox 执行、重试分类和上下文恢复装配, so that 业务 App 不需要重复理解底层组件细节。
37. As a Messaging 组件使用者, I want 业务 Command 和 Event 仍留在各自 Service API, so that Starter 不会演变成装满业务 DTO 的 common 模块。
38. As a Messaging 组件使用者, I want 消息信封包含 message ID、type、version、tenant、actor、original initiator、correlation、causation 和 occurred-at, so that可靠传递和可追踪性有稳定契约。
39. As a Messaging 组件使用者, I want 每个订单相关消息使用稳定的 tenant-plus-order partition key, so that 同一订单的消息顺序可控而不同订单可并行。
40. As a Messaging 组件使用者, I want 可重试故障进行有界重试并最终进入 DLQ，非重试故障直接隔离, so that 毒消息不会无限阻塞消费。
41. As an Operations 维护者, I want 在 PostgreSQL 提交后 Kafka 不可用时订单仍保持可恢复, so that Broker 短暂故障不会丢失业务命令。
42. As an Operations 维护者, I want Outbox relay 在应用重启后继续认领未发布记录, so that 进程崩溃不会要求人工修复数据。
43. As an Operations 维护者, I want 即使消息已发送但 Outbox 尚未标记发布，重复发送也不会产生重复业务副作用, so that 失败窗口有明确的一致性语义。
44. As an Operations 维护者, I want 日志使用 correlation、message、tenant、order 和 application identity 等结构化字段, so that 跨服务问题可以定位且不打印 token 或敏感 payload。
45. As a Platform 使用者, I want BOM 统一管理所有受支持组件的版本但不隐式启用能力, so that 依赖声明简洁且 supported surface 不被误读。
46. As a Platform 使用者, I want Web 与 OAuth2 Resource Server 保持独立组件, so that 纯 Web 应用不会被隐藏安全策略绑架。
47. As a Platform 使用者, I want Data Starter 在缺少事务管理器或关键租户装配时启动失败, so that 系统不会静默退化为无事务或无租户隔离模式。
48. As a Platform 使用者, I want 自定义 MyBatis-Plus InnerInterceptor 时平台 TenantLine 仍然存在, so that 普通扩展不会意外关闭租户保护。
49. As a Platform 使用者, I want 分页、错误、上下文、授权、标识和事务契约留在一个按 package 隔离的 `platform-kernel`, so that 人工与 AI 不必跨多个浅 Maven 模块导航。
50. As a Platform 使用者, I want Messaging 保持独立于 Kernel, so that消息可靠性和 schema 演进能拥有自己的消费者与验证生命周期。
51. As a Platform 使用者, I want 每个 Starter 对应清晰的组件类型并隐藏装配细节, so that 依赖列表能直接表达 App 使用了什么能力。
52. As a Platform 使用者, I want App 装配成为唯一 composition root, so that 业务模块不通过 component scan 偶然选择本地或远程适配器。
53. As a Platform 使用者, I want PostgreSQL/MyBatis-Plus 作为默认文档和性能基线, so that 黄金路径只有一套清晰答案。
54. As a Platform 使用者, I want MySQL 通过相同 Repository 与 migration TCK 保持兼容, so that 未来切换数据库时有可量化适配成本而不是维护第二套 E2E。
55. As a Platform 使用者, I want Local implementation 继续可用于直接测试但不成为首版验收依赖, so that 微服务部署能力先得到真实证明。
56. As a QA 工程师, I want 从公共 HTTP 开始并通过公共 HTTP 观察最终订单状态, so that 验收测试证明的是可运行产品而不是内部 Bean 组合。
57. As a QA 工程师, I want 每次验收运行使用隔离的数据库、Kafka 命名空间和 Run ID, so that 并行执行和重复执行不会互相污染。
58. As a QA 工程师, I want 使用 bounded Eventually assertion 等待异步完成, so that 测试不会依赖固定 sleep 或用无限重试掩盖故障。
59. As a QA 工程师, I want 能从进程外停止和恢复 Broker 或 App, so that 故障恢复在不增加测试专用生产端点的前提下可验证。
60. As an AI 编码代理, I want 从一个完整参考业务看到 Service API、Application Service、Repository、HTTP adapter、message entry 和 App 装配的标准位置, so that 新功能可以遵循已证实路径而不是重新推理架构。
61. As an AI 编码代理, I want 架构测试禁止 Domain/Application 依赖 Spring、ORM、Kafka 和 `ExecutionContextHolder`, so that 自动生成代码越界时能立即得到可执行反馈。
62. As a Human 维护者, I want 每个新增 Maven 模块都同时拥有真实消费者和行为证据, so that 脚手架不会再次累积空壳或假设性 seam。
63. As a Human 维护者, I want README 用一条命令验证脚手架并用一条命令运行完整参考流, so that 成品可以被实际体验而不是只能阅读设计文档。
64. As a Human 维护者, I want 当前、incubating、planned 和 deferred 能力有单一状态来源, so that 文档、BOM 和代码不会给出互相矛盾的支持声明。

## Implementation Decisions

- 运行基线固定为 JDK 26、Maven Wrapper 3.9.16、Spring Boot 4.1.1、Spring Cloud 2025.1.3、MyBatis-Plus 3.5.17、PostgreSQL 18.6 测试镜像，以及 Spring Cloud Stream 5.0.x Kafka Binder。实现必须通过仓库自身验证这些组合，不把上游未声明的组合兼容性当作事实。
- 黄金路径仍是多进程微服务：`gateway-app`、`identity-app`、`catalog-app`、`order-app`、`inventory-app` 分别作为 App 装配运行。后续 App Assembly 规格已把 Catalog、Order、Inventory 组成的业务核心模块化单体提升为第二种受支持拓扑；它继续以独立 Gateway 与 Identity 作为信任边界，不替代微服务 Golden Path。
- App 装配只负责启动、配置和适配器选择。业务行为留在现有 Catalog、Order、Inventory Service API 与 Service 模块中。仅在出现真实同步消费者时新增远程 client；本规格要求 Order 使用 Catalog HTTP client，因此该 client 是真实 seam。
- `platform-dependencies` 继续作为平台全局 dependency-management BOM。BOM 可以治理 active 与 candidate 版本，但文档和 consumer smoke 必须区分“坐标受管”与“能力已启用/已支持”。业务 App 的平台依赖不写版本。
- `platform-kernel` 保留 paging、coded error、Execution Context、authorization、identifier 与 framework-free `TransactionBoundary`，通过 Java package 隔离概念，不再为每组轻量 contract 建浅 Maven 模块。Kernel 不包含 Spring、ORM、Broker、业务 DTO、Persistence Row、BaseEntity、通用 Mapper/Repository 或 utility 集合。
- Web Starter 与 OAuth2 Resource Server 组件保持分离。Web Starter 提供原生 HTTP、validation、correlation 和 RFC 9457 错误处理；Resource Server 提供 JWT 验证、USER/SERVICE tenant 规则、Execution Context 生命周期和 401/403 行为。需要安全的 App 必须显式同时声明两者。
- Identity 只实现本黄金路径所需的闭环：正常 session login 发放 opaque browser token、Gateway 专用 token exchange、短生命周期 internal JWT、tenant-level RBAC，以及 USER/SERVICE/original initiator claim。演示身份由验收环境迁移或 fixture 建立；不得在生产代码中添加绕过验证的测试 token endpoint。完整账户生命周期不在本规格内。
- Gateway 接受 opaque token，通过受保护的内部契约向 Identity 交换 internal JWT，并转发给资源服务。Gateway 不改写 Service-owned API 版本，不包装成功响应，也不公开内部 Catalog、Identity exchange 或消息管理端点。
- 公共订单契约至少包含 `POST /api/v1/orders` 与 `GET /api/v1/orders/{orderId}`。创建请求只含订单行的 product ID 与正数量，不接受 price、currency、tenant、status、audit 或 version。创建成功返回 `201 Created`、`Location` 和未包装的 Order View；标识在 JSON 中使用 string，初始状态为 `PENDING_STOCK`。
- Order View 至少包含 order ID、status、currency、创建时间和产品/单价/数量快照。`REJECTED` 可返回安全、稳定的 unavailable product IDs；不得泄漏 SQL、topic、partition、堆栈或内部异常类。
- Catalog 提供内部版本化 HTTP 契约，对应现有 `CatalogApi`，返回下单所需的权威 Product Snapshot。Order 的远程 client 负责 HTTP、JWT/service identity、timeout、Problem Details mapping 与 correlation 传播；Order Application Service 仍只依赖 `CatalogApi`。
- Order 必须在进入 `TransactionBoundary` 之前完成 Catalog 远程调用。远程调用失败时不创建订单或 Outbox 记录；事务内禁止 HTTP、直接 Broker send、Redis 或其他远程副作用。
- `TransactionBoundary.inTransaction` 只表示一个 top-level、single-service、single-database 本地事务。Data Starter 使用 READ_COMMITTED 的 Spring 实现，缺少唯一 `PlatformTransactionManager` 时启动失败，嵌套边界必须失败而不是静默加入。
- Order 创建事务同时保存 Order 聚合和 `ReserveInventoryCommand` Outbox 记录。Order 结果消费事务同时保存 Inbox 记录和 Order 状态变化。Inventory 消费事务同时保存 Inbox、库存预留结果和结果 Event Outbox。以上原子性不得由 Kafka transaction 替代。
- `ReserveInventoryCommand` 由 Inventory Service API 拥有；`InventoryReserved` 与 `InventoryRejected` 是 Inventory 拥有的 Integration Event。业务 payload 不携带技术 Tenant ID，Tenant、actor、original initiator 和追踪信息由 Message Envelope 承载。
- 保留 purpose-named publisher/handler interface，使 Application Service 不依赖 Kafka、Spring Cloud Stream、Binder、`StreamBridge` 或通用 Event Bus。基础设施 adapter 将业务命令/事件序列化并写入 Outbox。
- 消息平台收敛为 broker-neutral `platform-message-core` 和一个 `platform-messaging-kafka-spring-boot-starter`。Core 拥有 Message Envelope、Outbox/Inbox 契约、重试分类、schema/version 与可靠性算法；Starter 拥有 Spring Cloud Stream imperative functions、Kafka Binder、序列化、partition key、relay/consumer 生命周期、DLQ 与 auto-configuration。业务 Command/Event 不进入平台组件。
- Kafka 是唯一当前 Broker 目标。RabbitMQ、RocketMQ 及其他 Binder 不建同等级模块、适配器或 E2E；未来仅在真实需求出现后按现有消息契约增加资格验证。
- Message Envelope 至少包含 message ID、message type、schema version、source、destination、tenant、actor/service identity、original initiator、correlation ID、causation ID、occurred-at 和 payload。消息入口先验证信封，再建立可信 Execution Context，并在处理结束后无条件清理。
- 订单相关 Command/Event 使用由 tenant ID 与 order ID 组成的稳定 partition key。系统依赖 at-least-once 而不是 exactly-once 承诺；Broker 发送成功但 Outbox 标记失败所产生的重复消息由 Inbox 与业务幂等共同吸收。
- Outbox 需要持久化状态、next-attempt、attempt count、claim token、lease deadline、published-at 和 last error classification。relay 只在业务事务提交后发送，支持并发 claim、lease 过期恢复、有界退避和进程重启续传。不可重试错误或超过上限的记录进入可观察的 terminal state/DLQ，不无限循环。
- Inbox 以 consumer identity 与 message ID 建唯一约束，在同一业务事务内完成 dedupe、handler 和记录。并发重复必须由数据库唯一约束兜底，不能只依赖先查后写。冲突 payload、未知 schema 或非法状态转换属于不可重试失败。
- PostgreSQL 是完整黄金路径和性能基线。每个业务服务拥有独立逻辑数据库、凭证、schema migration 与 Flyway history；本地验收允许共享一个 PostgreSQL server，但禁止跨服务表读取和跨数据库事务。
- Order 数据至少包含 order、order line、outbox 和 inbox。Inventory 数据至少包含 stock、reservation/result、outbox 和 inbox。Catalog 数据至少包含 tenant-owned product/price。Identity 数据至少包含 demo user/service identity、tenant membership、permission assignment 和 opaque session。所有业务 persistence records 显式声明 tenant、audit、identifier 和必要 version 字段，不继承 BaseEntity。
- 普通 Domain、Application 和 Repository interface 不接收 Tenant ID，也不读取 `ExecutionContextHolder`。HTTP/message entry 建立上下文；persistence adapter 在入口 fail closed 读取上下文、显式写入 Row tenant 字段并校验不匹配；TenantLine 负责 query/update/delete 防护，但不是唯一安全权威。缺少上下文时必须在执行 SQL 前失败。
- 每张多租户业务表使用 tenant-qualified 主键、唯一键和外键。Repository 使用 domain-specific Mapper 与显式 mapping，不把 `BaseMapper`、`IService`、通用 Wrapper、Persistence Row 或分页插件暴露给业务层。
- Inventory 对一个多行命令执行 all-or-nothing 预留。实现按 product ID 稳定排序，并使用可验证的条件更新、版本控制或行锁保证 `available >= requested`。任一行不足则整个事务回滚库存变化，并持久化唯一的 rejected result；重复 command ID 返回已持久化结果。
- Order 状态机只允许 `PENDING_STOCK -> CONFIRMED` 或 `PENDING_STOCK -> REJECTED`。结果必须匹配 order ID 和当前 command ID。相同结果重复投递无副作用；冲突结果、未知订单或非法跃迁不得覆盖已有状态，并按不可重试错误隔离。
- 数据库迁移继续由业务服务拥有，并通过 `platform-database-migration` 使用显式 component identity、私有 history、guarded baseline/migrate/validate。平台 Messaging Starter 可以提供其基础表的版本化 migration location，但 App 装配必须显式将其纳入本服务迁移集合，避免与 Boot 默认 Flyway 双执行。
- MySQL 是长期兼容目标，不是第二条黄金路径。相同的 Catalog、Order、Inventory Repository TCK、租户 TCK 和 migration behavior 必须在 MySQL 通过；允许在基础设施边界内使用数据库专属 SQL/migration。不得引入 weakest-common-SQL DSL，也不得恢复 MyBatis-Plus × jOOQ 的 2 × 2 矩阵。
- Starter 必须是深模块：提供清晰自动装配、配置属性、条件报告、失败模式和独立 consumer smoke。Data Starter 不得因消费者声明自己的 `MybatisPlusInterceptor` 而移除 TenantLine；Kafka Starter 不得在缺少 binding、serializer、transaction/data dependency 或 destination 时静默启动成残缺能力。
- Architecture rules 至少禁止 Domain/Application 依赖 Spring Web、Spring Security、MyBatis-Plus、Kafka、Spring Cloud Stream、Persistence Row、`ExecutionContextHolder` 和 test-support；禁止 production 依赖 test artifact；禁止通用 BaseEntity/BaseMapper/BaseRepository 进入业务模块。
- App 装配必须显式选择 HTTP client、persistence adapter、message adapter 和授权实现，不依赖跨 sibling package 的偶然 component scan。一个 App 的 classpath 不能同时激活 Local 与 Remote 实现。
- 可执行演示提供一个面向使用者的最短路径：先安装脚手架 artifacts，再启动依赖与五个 App，最后运行完整订单流。启动编排属于开发/验收 harness，不构成生产部署技术选型；Kubernetes、Helm 和云环境仍保持开放。
- Supported surface 的唯一状态来源必须更新为本实现的当前证据。只有独立 consumer、真实组件测试和黄金路径通过的能力才能标记 supported；其余继续标记 incubating、planned 或 deferred。

## Testing Decisions

- 最高、主要且唯一的产品验收 seam 是运行中的 Gateway 公共 HTTP 加稳定的 Kafka 消息契约。核心 happy path 从正常登录开始，只调用公共 Order HTTP API，并只通过公共 Order HTTP API观察 `PENDING_STOCK` 到 `CONFIRMED`/`REJECTED` 的最终结果。测试不获取 Spring Bean、不调用 Repository、不查询业务数据库，也不增加测试专用生产端点。
- 黑盒验收同时启动 Gateway、Identity、Catalog、Order、Inventory、PostgreSQL 与 Kafka。每次运行使用隔离数据库、topic/group 前缀和 Run ID；异步观察使用 bounded Eventually assertion，并在超时时输出各 App 日志、correlation、message ID、consumer group 与容器状态。不得用固定长 sleep 或自动重跑隐藏 flaky behavior。
- 公共 HTTP 验收覆盖：正常登录与 opaque token；无 token 为 401；错误 audience/过期 token 为 401；无权限为 403；validation 为 400 Problem Details；不存在或跨租户订单统一为 404；成功响应未包装；correlation 生成与透传；internal route 无法从 Gateway 访问。
- 核心业务验收覆盖：库存充足确认、库存不足拒绝、多行订单全成或全拒、Catalog 价格快照、tenant-a 创建后 tenant-b 不可见、重复查询稳定，以及所有 JSON identifier 为 string。
- 并发验收用多个租户内订单竞争有限库存，断言确认数量不超过可用库存、没有负库存、失败订单为 `REJECTED`，并且测试可重复。数据库级库存不变量在 Inventory PostgreSQL integration test 中直接验证，公共结果在黑盒 seam 验证。
- 故障恢复验收通过进程外控制依赖完成，不使用测试 endpoint。至少覆盖：Kafka 不可用时创建订单仍提交为 `PENDING_STOCK`，恢复 Kafka 或重启 Order 后 Outbox 自动发布并最终完成；发送后、标记 published 前的重复发送不会重复预留；Inventory 或 Order 在消费中断后重启可以从 Inbox/Outbox 状态恢复。
- 消息黑盒/TCK 使用真实 Kafka 覆盖 envelope serialization、schema version、destination、partition key、USER/SERVICE/original initiator context、correlation/causation、重复投递、同订单顺序、有界重试、non-retryable 分类和 DLQ。业务 E2E 不乘以第二 Broker。
- Domain 与 Application Service 使用快速 unit tests 覆盖状态机、单币种规则、价格快照、库存 all-or-nothing、幂等 command/result、非法跃迁和 authorization invocation。这里优先使用现有 Catalog、Order、Inventory 单元测试的风格，但用 persistence/message integration 取代内存实现作为完成证据。
- HTTP Service API contract tests 同时验证 Catalog 服务端 adapter 与 Order 远程 client 的正常返回、Problem Details mapping、timeout、correlation、身份传播和版本路径。Local implementation 可以复用协议无关 Service API TCK，但不要求复制所有远程故障用例。
- PostgreSQL Repository integration/TCK 使用真实数据库与真实 migration，覆盖 create/rehydrate/update、乐观冲突、tenant isolation、missing-context fail closed、显式错误 tenant 拒绝、复合键/外键、事务 commit/rollback 和并发。测试不得把 TenantLine 单独通过等同于租户安全成立。
- MySQL compatibility TCK 复用相同 Repository 与 migration behavior，覆盖核心事务、租户、审计、version 和查询语义；不运行 Gateway/Kafka/完整业务 E2E，也不要求 pgvector 等 PostgreSQL-native 能力一致。
- Transaction integration test 必须证明 Order+Outbox、Inventory+Inbox+reservation+Outbox、Order+Inbox+state change 分别同提交同回滚；必须证明 TransactionBoundary 内发生的远程调用或 nested boundary 被规则或运行时明确拒绝。
- Starter consumer tests 使用不继承脚手架 parent 的独立 Maven consumer，只 import BOM 并声明 versionless platform artifacts。Web、Resource Server、Data 和 Kafka Starter 均需启动真实或最小可信 context；关键 bean/condition 缺失必须得到明确启动失败而非静默 back-off。
- Migration tests沿用现有 DatabaseMigrator 的先 fail-closed、再 initialize/migrate/validate 模式；Data/Web consumer integration 沿用 Notes Demo 的独立 BOM、真实 HTTP、PostgreSQL、租户隔离和事务装配思路；架构测试沿用现有 Architecture Testkit 的可执行边界规则。Prior art 只提供测试结构，不限制本规格采用更高的多进程 seam。
- Architecture tests 和 Maven dependency checks 覆盖模块方向、禁止依赖、BOM 版本收敛、JDK/Boot/Cloud/MP 组合、production/test-support 隔离，以及每个新增 Maven 模块至少有一个真实 production consumer 或独立 consumer test。
- 验收完成标准是两段式：脚手架 reactor 的 `verify/install` 通过；随后独立多进程参考应用与黑盒 acceptance 通过。只通过 mapper CRUD、Spring contextLoads、内存消息测试或单个 Notes Demo 都不能宣称本规格完成。
- 不要求测试私有方法、Bean 数量之外的实现结构、SQL 逐字文本、框架内部回调次数或日志格式细节。测试断言外部契约、持久化不变量、事务原子性、隔离性、幂等性、恢复性和可观察关联字段。

## Out of Scope

- 支付、退款、发货、取消、补偿流程、通用 Saga/Process Manager、工作流 DSL、XA/Seata 或其他分布式事务运行时。
- 完整用户注册、找回密码、MFA、社交登录、组织生命周期、复杂 RBAC 管理 UI 或把 Identity 扩展为通用 IAM 产品。
- 前端页面、移动端、运营后台或 API Gateway 的流量治理产品化。
- 将 Gateway、Identity 与业务核心全部合并的完整后端单体，以及为业务核心模块化单体复制数据库/Broker 全组合矩阵。后续 App Assembly 规格只增加了由同一公共黑盒契约覆盖的聚焦业务核心单体拓扑。
- jOOQ、第二 ORM、RabbitMQ、RocketMQ、第二 Broker TCK 或运行时切换 Broker/ORM/拓扑。
- MySQL 完整端到端、性能基线或所有 PostgreSQL-native 扩展对等；MySQL 仅覆盖核心 Repository、migration 与租户兼容 TCK。
- AI Agent、RAG、pgvector、Embedding、模型调用和 AI 业务接口。PostgreSQL 的选择允许未来在业务拥有的 retrieval interface 后加入 pgvector，但本订单流不为此制造抽象。
- Object Storage、MinIO、Cache、Redis、Redisson、分布式锁、Cron、tenant fan-out scheduler、设备模拟器和通知系统，除非订单流出现无法由数据库与消息可靠性解决的真实需求。
- Kubernetes、Helm、生产云资源、Service Mesh、正式 CI cadence 和生产发布编排。开发/验收 harness 仅用于复现多进程行为，不代表生产部署决策。
- 通用 BaseEntity、通用业务 Mapper/Repository、DTO 自动映射框架、统一响应包装、weakest-common-SQL compatibility layer 或为候选能力创建空 Maven module。
- 在本规格内完成外部 MIT 母项目的最终选型。若另行选定母项目，它只能改变实现底座，不能降低这里的业务契约、架构边界与验收标准。

## Further Notes

- 本规格最初确认的验收 seam 优先证明真实微服务部署方式。后续 App Assembly 规格在不改变公共 HTTP、真实 Kafka 异步履约、PostgreSQL 持久化与恢复语义的前提下，用同一套黑盒步骤补充验收业务核心模块化单体；Repository、Starter 和 TCK 仍只是支撑证据。
- 当前仓库已有可复用的 Service API、Application Service、`TransactionBoundary`、Message Envelope、Outbox/Inbox 算法、Web/Auth/Data/Migration 组件和 Notes Demo。实现应深化这些真实 seam，并用持久化、远程和 Broker adapter 替换内存证据；不要平行创建第二套命名不同但语义相同的框架。
- 当前尚缺五个真实 App 装配、Catalog HTTP server/client、业务 PostgreSQL adapter/migration、Kafka Starter、durable Outbox/Inbox store、完整 Identity/Gateway 闭环以及独立黑盒 harness。Issue 完成状态必须以这些缺口全部关闭为准。
- 建议实施顺序是：先让 Kernel/BOM/Starter public surface 与失败模式稳定；再完成 Catalog/Order/Inventory PostgreSQL adapter 与 migration；随后完成 Identity/Gateway 和 Catalog HTTP 边界；再接入 Kafka Starter 与 durable Outbox/Inbox；最后建立多进程黑盒、故障恢复和 MySQL compatibility TCK。每一步都必须增加可运行证据，不创建等待未来填充的空壳模块。
- 该参考业务也是后续评估 MIT 母项目的中立 benchmark。若采用外部 MIT 代码，必须记录来源、commit、LICENSE/NOTICE 与保留的版权声明，把其作为实现底座而不是需求来源；最终仍在当前项目通过相同的 BOM、Starter、业务契约与黑盒验收。
- 完成后应让一个新业务开发者或 AI 只通过黄金路径文档完成相邻用例，并记录需要理解的平台概念、直接依赖和样板代码。如果业务实现仍需了解 TenantLine、Kafka header、JWT claim、Flyway history、Outbox lease 或 MP Wrapper，则对应 Starter/adapter 尚未达到本规格要求的深度。
