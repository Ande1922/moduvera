# 11 — 证明 Outbox/Inbox 故障恢复能力

**What to build:** 通过外部停止和恢复 Kafka 或业务 App，证明 PostgreSQL 已提交但消息尚未发送、消息已发送但尚未标记、消费中断和毒消息等失败窗口都不会丢失订单或重复产生业务副作用。

**Blocked by:** 10 — 通过 Gateway 暴露完整公共订单流.

**Status:** resolved

- [x] Kafka 不可用时公共订单创建仍提交为 `PENDING_STOCK`，Order 与 Outbox 保持原子且查询可用。
- [x] Kafka 恢复或 Order 重启后 relay 重新认领记录并最终完成履约，无需人工修改数据库。
- [x] relay claim token、lease deadline、attempt count、next attempt、published-at 和 terminal failure 状态持久化并可恢复。
- [x] 模拟消息已发送但 Outbox 尚未标记 published 的窗口，重复投递不造成重复库存预留或重复状态变化。
- [x] Inventory 或 Order 在 handler 事务中断后重启，Inbox 不会把未提交副作用误判为已完成。
- [x] retryable 错误有界退避，non-retryable 或耗尽错误进入 DLQ/terminal state，并提供安全、可关联的可观察信息。
- [x] 故障由进程/容器外部控制，不添加测试专用生产 endpoint，也不用固定长 sleep 或自动重跑掩盖 flaky behavior。

## Answer

由 `71d3f37` 完成。JDBC Outbox/Inbox、lease/token fencing、retry/terminal
状态和真实 Kafka 故障恢复路径已提交；仓库测试验证事务回滚、重复投递、
重启接管和有界失败分类，未引入测试专用生产端点。
