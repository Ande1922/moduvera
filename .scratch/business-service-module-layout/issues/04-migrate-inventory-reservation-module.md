# 04 — 迁移 Inventory 预留模块与 Inventory App

**What to build:** 将 Inventory 预留能力组织为业务模块，并通过统一的 Inbound Message Handler 层级、公开 Inbound Configuration、方向明确的 Outbound Adapters 和显式 Inventory App 装配保持可靠消费、原子预留与结果发布语义。

**Blocked by:** 02 — 提供通用启动迁移运行时与消息平台定义; External — 完成并集成“库存异步契约交接”; External — 完成并集成“移除不完整的 InMemory Adapter”规格.

**Status:** resolved

- [x] Inventory Module Configuration 只构造协议中立的 Application Service，不激活消息、Persistence、Migration 或运行环境实现。
- [x] Message Core 提供 `InboundMessageHandler` 及其 `CommandMessageHandler`/`EventMessageHandler` 分类接口；Reliable Message Consumer 内化信封映射、契约校验、可信上下文、重试与 Inbox，并在该可靠边界内调用统一 Handler。
- [x] 消息入口由公开 Inbound Configuration 固定 Consumer Bean、契约和 Handler Bean；package-private `ReserveInventoryCommandMessageHandler` 实现 `CommandMessageHandler`，负责 payload 转换与错误分类并直接调用 `InventoryApplicationService.reserve`，不引入一方法 Application 能力接口、同步 Inventory Service API 或无语义 Mapper。
- [x] MyBatis Persistence 与结果发布实现位于对应 Outbound Adapter，服务 Migration Definition 无副作用，Persistence 配置不执行迁移。
- [x] Inventory App 显式选择业务 Module、消息 Inbound、Persistence/Publication Adapters、服务与消息平台 Definitions，并保持 malformed Payload、Inbox/Outbox、可信上下文及 Reserved/Rejected 行为。

## Answer

- Implementation: `73fd9b2`; integrated by `640517fcdd87ddd4ce168295b0ddf05d69992002`.
- Reviews: Standards PASS; Spec PASS. Standards 复审确认 Inventory 消息架构规则非空命中新 Handler，且 App 对消息迁移配置的例外仅限精确 FQN。
- Verification: `./mvnw -pl framework/foundation/moduvera-message-core,framework/starters/moduvera-messaging-kafka-spring-boot-starter,services/inventory/inventory-service,apps/inventory-app -am verify` PASS，10/10 modules，包含 7 个 Inventory App 集成测试；`./mvnw -pl framework/testing/moduvera-architecture-testkit verify` PASS，26/26 tests，并通过 Spotless 与 PMD。
