# 08 — 闭合 Order 与 Inventory 异步履约

**What to build:** 将 Order 与 Inventory 的 Kafka bindings 接通，使用户直接访问 `order-app` 创建订单后，系统能够异步完成库存预留，并将 Order 最终更新为 `CONFIRMED` 或 `REJECTED`。

**Blocked by:** 06 — 交付 Order 创建、查询与 Durable Outbox; 07 — 交付 Inventory 幂等库存预留.

**Status:** resolved

- [x] Order 发布的 `ReserveInventoryCommand` 被 Inventory 消费，Inventory 的结果 Event 被 Order 消费，topic/destination 与 schema version 明确配置。
- [x] Order 结果 Inbox、状态变化和相关持久化操作在同一事务中提交或回滚。
- [x] Order 状态机只允许 `PENDING_STOCK -> CONFIRMED` 或 `PENDING_STOCK -> REJECTED`。
- [x] 相同结果重复投递为无副作用 no-op；command/order 不匹配、冲突结果和非法跃迁不会覆盖合法状态。
- [x] 通过真实 HTTP 创建库存充足与库存不足订单，并使用 bounded Eventually assertion 查询到两种最终状态。
- [x] 订单保留创建时的产品名称、单价、数量和币种快照，最终状态变化不重新读取 Catalog。
- [x] Correlation、causation、tenant、service actor 与 original initiator 在 HTTP、两次消息和结果查询链路可关联。
