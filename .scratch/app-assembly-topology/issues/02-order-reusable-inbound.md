# 02 — 让 Order HTTP 与库存结果入站行为可复用

**What to build:** 让 Order 服务唯一拥有公共 HTTP、库存结果 Event 的契约校验、payload mapping 和用例调用；独立 Order App 只显式选择 Remote `CatalogApi`、Application、Persistence/Outbox、HTTP 与消息 Inbound slices，并保持现有订单创建、查询和 Kafka 状态推进语义。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] 库存结果 Consumer 及其 Inbound Message Contract、反序列化和 handler 从叶子 App 移入 `order-service` 的明确 Inbound Adapter package。
- [x] 公共 Order Controller、消息 Consumer、Persistence/Outbox 和 Remote `CatalogApi` 可由独立配置入口选择，独立 App 明确选择且只选择 Remote `CatalogApi`。
- [x] 现有 HTTP、远程 Catalog、Outbox、Kafka 消费、Inbox 去重、可信上下文和最终状态 integration tests 继续通过。
- [x] App 配置不再声明业务 payload 转换、消息契约或业务 handler lambda。

## Answer

由 `ea72d7d` 实现。Order HTTP 与库存结果 Inbound Adapter 已由服务拥有，
独立 App 只负责选择配置；当前 Order 聚焦测试和架构验证覆盖 HTTP、远程
Catalog、Outbox/Kafka 与可信执行上下文。
