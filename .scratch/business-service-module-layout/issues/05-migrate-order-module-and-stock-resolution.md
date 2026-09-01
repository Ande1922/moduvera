# 05 — 迁移 Order 模块、库存结果入口与 Order App

**What to build:** 将 Order 创建、查询和库存结果消解保持在同一业务模块，通过明确的 `resolvePendingStock` 意图、可复用消息 Inbound Adapter、Remote Catalog 与 Persistence/Publication Outbound Adapters 运行独立 Order App。

**Blocked by:** 03 — 迁移 Catalog 参考业务模块与 Catalog App; 04 — 迁移 Inventory 预留模块与 Inventory App.

**Status:** resolved

- [x] Order Module Configuration 只构造协议中立的 Application Service，创建、查询和待库存消解继续共享同一 Order 模型与事务语义。
- [x] Application 不再暴露意图模糊的一方法 Inventory Result Handler 或能力接口；package-private Inventory Result Message Handler 实现 `EventMessageHandler`，保留合法 Mapper 所保护的结果序列化差异并直接调用 `OrderApplicationService.resolvePendingStock`。
- [x] HTTP、消息、Remote Catalog、MyBatis Persistence 与 Outbox Publication 按 Inbound/Outbound 方向归属，Order App 显式选择 Remote Catalog 和所有必需切片。
- [x] Reserved、Rejected、重复或冲突终态、公共 HTTP、远程 Catalog、Outbox 与跨服务 Repository 集成测试保持通过，并更新所有已迁移实现类型的下游测试引用。

## Answer

- Implementation: `903356f`，最终 Ticket 结果 `e00ed1c`; integrated by `1c80e64c8b6b18d55dc380eb30e6e5acea878f7d`.
- Shared verification prerequisite: `b222a17`，集成提交 `2723f12739c0800227b4ea208b84de64eb44177b`；提供经独立 Standards/Spec 复审通过的 `InboundMessageContractTck`。
- Reviews: Standards PASS after resolving the shared inbound-contract TCK finding; Spec PASS.
- Verification: `./mvnw -pl services/order/order-service,apps/order-app -am verify` PASS，17/17 modules；Order Service 16 unit tests 与 5 integration tests、Order App 6 integration tests 全部通过，并覆盖 PostgreSQL、MySQL、Kafka/Testcontainers、Spotless、PMD 与 JaCoCo。
