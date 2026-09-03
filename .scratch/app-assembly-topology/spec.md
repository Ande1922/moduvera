Status: resolved

# 支持微服务与业务核心模块化单体的 App 装配

## Problem Statement

当前代码把部分业务入站语义放进了叶子 App 装配：Order 的库存结果 Consumer 和 Inventory 的预留命令 Consumer 由各自 App 定义，而 Gateway 还通过手写业务 Controller 重新实现登录、创建订单和查询订单端点。与此同时，`app-monolith` 仍是只有启动类和 `contextLoads` 的空壳，无法组合 Catalog、Order、Inventory 完成相同业务流。

这使已有的“双应用拓扑支持”承诺无法成立。把 Consumer 留在独立 App 意味着模块化单体无法复用消息入口；让 Gateway 拥有业务 Controller 则形成第二套公共 HTTP 实现，使接口校验、错误映射、版本和响应语义可能与业务服务分叉。现有业务 Controller 还直接包含 `/api` 路径，而典型 Gateway 路由又会携带并去掉服务名称，导致独立服务和多服务单体难以使用同一个 Controller 与同一组外部 URL。

需要形成一条确定、可验证的装配规则：业务服务唯一拥有 HTTP 和消息入站映射，App 装配只选择并激活这些映射及 Local/Remote 协作者；Gateway 只执行 Route/Filter；微服务与业务核心模块化单体继续使用同一 Kafka 可靠消息语义，并对客户端暴露完全相同的服务名称路由。

## Solution

把 Catalog、Order、Inventory 的可复用 HTTP Controller 和消息 Consumer 视为提供方业务服务拥有的 Inbound Adapter。它们按明确 package 和可独立导入的 Spring configuration slice 保留在现有普通 `*-service` Jar 中，不为只有一个实现的传输方式新增 Maven artifact。叶子 App 装配显式选择业务核心、入站适配器、Persistence、Kafka 和 Local/Remote Service API 实现，不再承载协议到业务用例的映射语义。

完成两种受支持应用拓扑。微服务 Golden Path 继续运行独立 Catalog、Order、Inventory App；Order 通过 Remote `CatalogApi` 调用 Catalog。业务核心模块化单体由一个 `app-monolith` 组合 Catalog、Order、Inventory，Order 改用 Local `CatalogApi`。两种拓扑都继续通过 Kafka、Spring Cloud Stream、transactional Outbox/Inbox、at-least-once delivery、可信消息上下文和既有重试恢复语义完成 Order 与 Inventory 的异步协作，不引入 `LocalTransport`，也不复制 Broker 验证矩阵。

删除手写 `GatewayController` 及其业务代理职责，以真正的 Gateway Route/Filter 转发登录和业务请求。公共外部路径稳定为 `/api/{service}/{service-owned-path}`；业务 Controller 只声明服务本地、带主版本的路径。Gateway 路由到独立服务 App 时去掉 `/api/{service}`；路由到组合多个业务服务的 App 时保留完整路径，由组合 App 为各服务的公共 Controller 增加对应前缀。每条路由只能执行一次前缀变换，Internal Controller 永不参与公共前缀处理。

第一版模块化单体只组合 Catalog、Order、Inventory。Gateway 和 Identity 继续作为独立外部信任边界 App；Gateway 访问 `app-monolith` 时保留业务服务前缀，访问独立 Identity 时按单服务规则去除其前缀。未来可以设计包含 Gateway 和 Identity 的完整后端组合，但不能重新引入 Gateway 业务 Controller、重复公共映射或第二套 Service API。

## User Stories

1. As a 脚手架使用者, I want 在微服务和业务核心模块化单体之间选择构建拓扑, so that 同一套业务代码可以适应不同部署规模。
2. As a 脚手架使用者, I want 两种拓扑具有明确且受测试约束的支持边界, so that “可以编译”不会被误认为“受支持”。
3. As a 业务服务开发者, I want HTTP Controller 由提供该能力的业务服务唯一拥有, so that 请求校验、版本、错误和用例映射只有一个实现。
4. As a 业务服务开发者, I want 消息 Consumer 由接收命令或事件的业务服务拥有, so that消息契约和处理语义不会被部署 App 复制。
5. As a 业务服务开发者, I want Controller 和 Consumer 可以被不同 App 装配重复激活, so that 入站适配器不依赖某个固定进程拓扑。
6. As a 业务服务开发者, I want Inbound Adapter 与 Domain/Application 通过 package 保持清晰边界, so that Spring Web、Spring Messaging 和 Kafka 类型不会污染业务核心。
7. As a 业务服务维护者, I want 暂时把唯一的入站实现留在普通 `*-service` Jar, so that 不会为了假设性的第二实现增加浅 Maven 模块。
8. As an App 装配维护者, I want 显式导入所需的业务核心和入站 configuration slice, so that component scan 不会偶然激活错误适配器。
9. As an App 装配维护者, I want App 只负责选择、配置和激活实现, so that 协议到用例的业务映射不会漂移到启动模块。
10. As an Order App 维护者, I want 在微服务拓扑中显式选择 Remote `CatalogApi`, so that Order 与 Catalog 保持真实 HTTP 边界。
11. As a Monolith App 维护者, I want 在组合拓扑中显式选择 Local `CatalogApi`, so that 同进程服务不需要绕行 HTTP。
12. As a Monolith App 维护者, I want 每个必需 Service API 在运行时恰好存在一个实现, so that Local 与 Remote 不会同时激活或依赖 Bean 优先级碰巧工作。
13. As a Monolith App 维护者, I want Catalog、Order、Inventory 在一个可执行入口中完成同一参考业务流, so that `app-monolith` 不再是空壳。
14. As an Order 开发者, I want Local 和 Remote Catalog 调用共享同一个协议中立 Service API, so that业务用例不感知进程拓扑。
15. As an Inventory 开发者, I want `ReserveInventoryCommand` 的消费映射随 Inventory 业务服务复用, so that独立 Inventory App 和组合 App 使用同一处理入口。
16. As an Order 开发者, I want库存结果 Event 的消费映射随 Order 业务服务复用, so that两种拓扑以相同规则推进订单状态。
17. As a Messaging 组件使用者, I want 两种拓扑都使用同一 Kafka 传输, so that 不需要维护语义不同的本地消息总线。
18. As a Messaging 组件使用者, I want 两种拓扑都保留 transactional Outbox/Inbox 和 at-least-once 语义, so that部署合并不会削弱可靠性、幂等或恢复能力。
19. As a Messaging 组件使用者, I want 两种拓扑都通过可信消息入口建立并清理 Execution Context, so that租户、Actor、Initiator 和 Correlation 语义保持一致。
20. As an Operations 维护者, I want 模块化单体仍可在 Kafka 故障恢复后继续处理未发布和重复消息, so that单体不是只适用于无故障 Demo 的降级实现。
21. As a Gateway 维护者, I want Gateway 只包含 Route 和 Filter, so that边缘层不会成为第二个业务接口实现。
22. As a Gateway 维护者, I want 删除手写业务代理 Controller, so that参数校验、响应复制和路径拼接不再由 Gateway 重写。
23. As a Gateway 维护者, I want 登录、鉴权转换、Header 传播和外部访问控制继续由边缘 Filter 负责, so that消除业务 Controller 不会移除信任边界能力。
24. As a Gateway 维护者, I want `/internal/**` 永不成为公共 Route, so that组合拓扑不会意外暴露内部 Service API。
25. As an API 消费者, I want 微服务与模块化单体暴露相同的 `/api/{service}/...` URL, so that切换部署拓扑不要求修改客户端。
26. As an API 消费者, I want API 主版本继续由业务 Controller 拥有, so that版本语义不依赖 Gateway 部署配置。
27. As a 业务服务开发者, I want Controller 只声明服务本地路径, so that服务发现名称和部署路由不会泄漏进业务接口映射。
28. As a Gateway 维护者, I want 路由到独立服务时去掉服务名称前缀, so that单服务 App 只处理自己的本地路径。
29. As a Monolith App 维护者, I want 为不同业务服务的公共 Controller 增加不同服务前缀, so that相同的本地路径不会在一个应用中冲突。
30. As a Gateway 维护者, I want 路由到多服务 App 时保留服务名称前缀, so that组合 App 能确定应由哪个服务 Controller 处理请求。
31. As an App 装配维护者, I want 每条公共路由只发生一次前缀变换, so that配置错误不会产生双前缀或无法匹配的路径。
32. As an Internal API 维护者, I want Internal Controller 排除在公共路径前缀规则之外, so that内部调用契约不会因外部拓扑改变。
33. As an Identity 维护者, I want 第一版继续以独立 Identity App 提供身份能力, so that业务核心单体改造不会扩大外部信任边界范围。
34. As a 平台架构维护者, I want Gateway 和 Identity 的完整合并推迟到真实拓扑需求出现时, so that当前实现不为假设性组合制造接口或模块。
35. As a QA 工程师, I want 同一套公共 HTTP 黑盒契约运行于两种拓扑, so that客户端可见兼容性由执行证据而不是文档声称保证。
36. As a QA 工程师, I want 黑盒测试同时观察最终 Kafka 驱动的订单结果, so that测试证明完整业务流而不仅是路由匹配。
37. As a QA 工程师, I want 测试证明公共错误、鉴权、租户隔离和 Correlation 在两种拓扑一致, so that拓扑切换不会改变安全与诊断语义。
38. As a QA 工程师, I want 架构测试禁止 Gateway 中出现业务 Controller, so that后续修改不能重新引入已删除的第二套端点。
39. As a QA 工程师, I want 架构测试禁止 `*-api` 依赖 HTTP 或 Gateway 类型, so that Service API 始终只表达内部 Local/Remote 协作。
40. As an AI 编码代理, I want 从明确的模块和 package 规则判断代码归属, so that生成 Controller、Consumer 或装配代码时不需要重新推断架构。
41. As a Human 维护者, I want 新 Maven artifact 只在第二个真实实现出现时创建, so that代码库保持深模块并降低导航成本。
42. As a Human 维护者, I want 支持状态在完成两种拓扑验收后才提升, so that不完整的 `app-monolith` 不会被提前宣传为可用产品。

## Implementation Decisions

- ADR 0022 至 ADR 0029 是本规格的架构依据；与其冲突的早期“模块化单体可选”或“Gateway 拥有业务端点”描述被后续决策取代。
- 受支持拓扑只有构建时选择的多进程微服务和业务核心模块化单体；禁止在同一个运行实例中动态切换 Local/Remote 实现。
- 微服务仍是完整 Golden Path。第二个拓扑是必须完成聚焦验收的产品能力，但不复制数据库、Broker、故障场景和所有基础设施组合的笛卡尔积。
- 第一版业务核心模块化单体只组合 Catalog、Order、Inventory。Gateway 和 Identity 保持独立 App，并继续构成外部信任边界。
- `app-monolith` 必须成为真实 composition root，显式组合三项业务能力、它们的公共或消息 Inbound Adapter、Persistence、Kafka 以及拓扑所需的 Local Service API 实现。
- 每个提供方业务服务唯一拥有其 HTTP Controller 和消息 Consumer 的协议映射、契约校验、序列化转换及应用用例调用。
- Controller 和 Consumer 放在现有普通 `*-service` Jar 内的明确 Inbound Adapter package，并通过可独立导入的 Spring configuration slice 激活。当前不新增 `*-web`、`*-messaging` 或类似传输 Maven artifact。
- App 装配不得定义业务 Controller、消息 payload 转换、Inbound Message Contract 或业务 handler lambda；它只选择配置 slice、基础设施实现、binding、destination、端口和拓扑协作者。
- 业务核心、Persistence、公共 HTTP Inbound、Internal HTTP Inbound、消息 Inbound、Local collaborator 和 Remote collaborator 必须能够被 App 分别选择，避免一个总配置类同时强制启用所有拓扑选择。
- 独立 `order-app` 选择 Remote `CatalogApi` HTTP adapter；`app-monolith` 选择由 Catalog Application Service 提供的 Local `CatalogApi`。一个 Application Context 中每项必需 Service API 必须恰好有一个实现，缺失或重复时启动失败。
- `order-app` 与 `app-monolith` 激活同一个 Order 公共 Controller 和库存结果 Consumer；`inventory-app` 与 `app-monolith` 激活同一个库存预留 Consumer。两种拓扑不得复制 handler 或 payload mapping。
- 两种拓扑都使用 Spring Cloud Stream/Kafka Binder 和现有可靠消息层；Order 与 Inventory 即使位于同一进程，也通过同一序列化消息、Destination、Outbox relay、Kafka binding、Reliable Consumer 和 Inbox 交互。
- 不实现 `LocalTransport`，也不为模块化单体增加进程内 Event Bus。未来替换 Kafka 的实现必须位于消息中间件边界之后并通过同一语义契约，而不是改变业务 Consumer。
- Gateway 必须删除现有手写业务代理 Controller。登录、订单创建和订单查询通过 Route/Filter 到提供方 Controller，不在 Gateway 中解析业务 Path Variable、复制业务响应、执行业务校验或构造业务 Problem Details。
- Gateway 保留边缘职责：路由选择、opaque browser session 处理、Identity token exchange、内部 JWT/Header 传播、Correlation 处理、外部访问控制以及拒绝 Internal Route。对应逻辑必须实现为路由或 Filter 能力。
- Service API 是业务服务之间协议中立的内部 Local/Remote 调用契约，不是公共 HTTP、Gateway 接口或浏览器 API。不得为了 Gateway 创建或扩展 `*-api`。
- 公共外部路径格式固定为 `/api/{service}/{service-owned-path}`。Order 的公共订单路径因此使用 Order 服务前缀和 Controller 拥有的主版本；Identity 的公共登录路径使用 Identity 服务前缀。具体服务键必须在 Gateway 与组合 App 配置中唯一且一致。
- 业务公共 Controller 只声明服务本地、带主版本路径，例如 `/v1/orders`。`/api/{service}` 是 External Route Prefix，不属于 Service API、Controller 业务契约或 API 主版本。
- Gateway 路由到独立服务 App 时，根据服务键选定目标并在转发前去掉 `/api/{service}`。独立提供方接收 Controller 声明的本地路径。
- 组合多个业务服务的 App 按 Controller 类型或 package 为每个服务的公共 Controller 增加唯一 `/api/{service}` 前缀。Gateway 路由到该 App 时保留完整路径；直接暴露组合 App 时也使用同一完整外部路径。
- 同一 Route 只能选择“Gateway 去前缀”或“组合 App 加前缀”中的一种有效变换，不能同时应用或都不应用。启动验证和契约测试必须发现双前缀、缺失前缀、未知服务键及重复映射。
- Path prefix predicate 只匹配公共 Controller。Internal Controller、Actuator、JWKS、token exchange、service-token 和其他内部能力不因公共服务前缀规则自动改写。
- API 主版本、missing-version 行为、请求校验、成功响应、Location、RFC 9457 Problem Details 和业务错误码仍由业务 Controller/Web 能力拥有；Gateway 应透明传播而不重新实现。
- 现有 Identity 提供方在第一版保持独立。移除 Gateway 对登录端点的复制，但本规格不要求为了未来完整合并立即创建 Identity Service API 或拆分新的 Identity Maven 模块。
- Persistence 数据所有权、Migration、租户隔离、TransactionBoundary 和数据库支持矩阵不因拓扑变化而改变。组合 App 不允许通过 Repository 或数据库表绕过业务服务边界。
- `app-monolith` 晋升为 supported 的条件是聚焦端到端验收通过；在此之前产品状态仍为 planned/incubating，不能仅凭启动成功标记 supported。
- 实施完成后同步 README、Context、模块地图、产品范围和历史规格中的支持状态，避免旧的“单体可选”结论继续影响实现者。

## Testing Decisions

- 用户确认的最高产品 seam 是一套拓扑参数化的公共 HTTP 黑盒契约。契约分别运行于“Gateway 路由到独立 Catalog/Order/Inventory Apps”和“Gateway 路由到组合 Catalog/Order/Inventory 的 `app-monolith`”，不得复制成两套断言不同的测试。
- 两次运行从相同外部路径开始，仅通过 Gateway 公共 HTTP 创建和查询订单，并通过公共结果观察 Kafka 驱动的最终状态。测试不得获取 Spring Bean、调用 Repository、查询业务表或使用测试专用生产端点来证明 happy path。
- 路由契约明确验证：独立 Order 目标收到去除服务前缀后的本地路径；多服务 Monolith 目标收到保留服务前缀的完整路径；两种模式向客户端暴露相同 URL、状态码、响应体、Location 和 Correlation。
- 公共 HTTP 契约覆盖正常登录、订单创建、订单查询、请求校验、无会话、无权限、不存在资源、跨租户不可见、RFC 9457 错误和未包装成功响应。
- 黑盒契约继续验证 `/internal/**` 不能通过 Gateway 访问，并验证公共前缀规则没有意外改写 Internal Controller、Actuator 或身份内部端点。
- 业务流契约覆盖库存充足确认、库存不足拒绝、重复消息无重复副作用和 bounded Eventually 完成；两种拓扑使用相同 Kafka、Outbox/Inbox 和可靠消费证据。
- 共享验收 harness 应允许以拓扑配置切换目标 App 集合、Gateway prefix policy 和服务地址，而不改变客户端步骤或预期业务结果。固定 sleep、无限重试和针对某一拓扑放宽断言均不允许。
- 第二个必要 seam 是现有 Architecture Testkit/仓库架构测试，因为“Gateway 不拥有业务 Controller”和“App 不拥有入站映射”无法仅从相同外部行为证明。
- 架构测试禁止 Gateway App 声明业务 `@RestController`、`@RequestMapping` 或业务响应代理；允许的边缘扩展必须属于 Route、Filter、安全或基础设施配置。
- 架构测试保证 `*-api` 不依赖 Spring Web、Gateway、Controller 或传输 DTO；公共 HTTP 路径不能出现在 Service API 中。
- 架构测试保证 Domain/Application package 不依赖 Spring Web、Spring Messaging、Spring Cloud Stream、Kafka 或具体 Reliable Consumer 类型。
- 架构测试保证业务 Controller/Consumer 位于提供方 Service 的 Inbound Adapter package，叶子 App 只能导入配置，不能声明业务映射 bean。
- App context integration tests 分别验证独立 App 和 `app-monolith` 的 Bean 选择：每项必需 Service API 恰好一个实现；独立 Order 使用 Remote Catalog；Monolith 使用 Local Catalog；未选择的实现不存在。
- 路径映射 integration test 验证多服务 App 对每组公共 Controller 应用正确且唯一的服务前缀，并验证 Internal Controller 不受影响。测试断言注册后的外部行为，不绑定具体 `PathMatchConfigurer` 方法调用或私有配置结构。
- 消息配置 integration test 验证同一个 Order/Inventory Consumer configuration slice 可被独立 App 与 Monolith 激活，并由相同 Inbound Message Contract 调用相同 Application Service。测试不重复验证 Kafka Starter 已覆盖的序列化算法内部细节。
- Gateway integration test 应以真实 Route/Filter 和轻量下游 HTTP stub 验证目标选择、prefix policy、token exchange/Header 传播、Correlation、状态与响应头透明转发；不再以手写 Controller 方法作为测试入口。
- Prior art 包括现有 Gateway Application integration test、Order/Inventory Application integration test、五 App 公共黑盒 harness，以及现有 Architecture Testkit。实现应迁移并提升这些测试，而不是并行创建第三套 harness。
- 完成标准要求 reactor 验证、相关 App integration tests、架构测试和两种拓扑的共享公共黑盒契约全部通过。只有 `contextLoads`、直接 Controller 单测或单一拓扑通过都不足以完成本规格。
- 测试只断言外部路径、安全边界、业务结果、可靠性语义和模块依赖规则；不测试私有方法、Bean 名称、package 之外的内部类组织或 Spring 配置调用次数。

## Out of Scope

- 将 Gateway 和 Identity 合并进第一版模块化单体，或完成未来全后端单体的安全与启动设计。
- 为 Identity 预先创建 `identity-api`、`identity-service` 或新的传输 artifact；第一版只消除 Gateway 对 Identity 公共端点的重复实现。
- 引入 `LocalTransport`、进程内 Event Bus、Spring Modulith Event 替代 Kafka，或让单体绕过 Outbox/Inbox。
- RabbitMQ、RocketMQ、第二 Broker、运行时 Broker 切换，以及复制完整消息中间件支持矩阵。
- 运行时切换微服务/单体拓扑，或在同一 Context 中同时激活一个 Service API 的 Local 与 Remote 实现。
- 为当前唯一 Controller/Consumer 实现拆分新的 `*-web`、`*-messaging`、`adapter-in` Maven 模块。
- 改变数据库支持矩阵、跨服务共享 Repository/表、XA/Seata、分布式事务或新的部署方案。
- 重新设计业务 API payload、订单状态机、Identity token 模型、RBAC、租户语义或 Kafka 消息契约。
- Kubernetes、Helm、Service Mesh、生产服务发现、灰度发布和生产路由配置分发。
- 保证任意数据库、Broker、Gateway 实现与两种拓扑的笛卡尔积；支持声明只覆盖本规格规定的聚焦验收。

## Further Notes

- 本规格细化并落实 ADR 0022 至 ADR 0029，尤其是“两个应用拓扑均受支持”“两种拓扑共用 Kafka”“业务服务拥有 Inbound Adapter”“Package-first”“第一版只组合业务核心”“Gateway 无业务 Controller”和“External Route Prefix 由装配处理”。
- 当前实现证据显示，Order 与 Inventory 的消息 Consumer 仍位于叶子 App 配置，Order 模块配置同时装配 Remote Catalog、Persistence、Application Service 和 Controller，Gateway 仍通过手写 Controller 与通用 HTTP client 代理业务请求，而 `app-monolith` 只有空启动入口。这些是本规格需要关闭的主要差距。
- 本规格比已有多租户订单履约规格更新且范围更窄；凡涉及模块化单体是否受支持、Consumer/Controller 所有权、Gateway 业务端点和服务名前缀的冲突描述，以本规格和后续 ADR 为准。
- Spring MVC 已提供按 Controller 类型谓词追加路径前缀的能力，Spring Cloud Gateway 已提供去除路径前缀的 Filter；实现可以使用这些现有 seam，但验收绑定的是路由语义而不是特定框架 API。
- 推荐实施顺序是：先拆分可复用业务配置 slice 并迁移 Consumer；再把独立 Apps 改成显式装配；随后删除 Gateway 业务 Controller 并建立 Route/Filter；最后完成 `app-monolith`、路径前缀配置和共享双拓扑黑盒验收。
- 所有改动必须保留现有用户修改，并避免把本规格之外的 incubating/deferred 能力顺带提升为 supported。
