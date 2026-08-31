# 04 — 跑通 Kafka 到 PostgreSQL Inbox 的幂等消费链路

**What to build:** 完成 Kafka Starter 的消费 tracer：真实 Kafka 消息经过信封验证、Execution Context 建立、Inbox 去重和本地事务后产生一次持久化业务副作用，并对失败实施有界重试和 DLQ 隔离。

**Blocked by:** 03 — 跑通 PostgreSQL Outbox 到 Kafka 的发布链路.

**Status:** resolved

- [x] imperative Consumer 在调用业务 handler 前验证 destination、schema version 和必需 Envelope 字段。
- [x] 消息入口从 Envelope 建立 tenant、service actor、original initiator 和 correlation context，并在成功、失败和取消后清理。
- [x] Inbox 记录与业务副作用在同一 `TransactionBoundary` 中提交或回滚。
- [x] 相同 consumer identity 与 message ID 重复投递不会再次产生业务副作用，并由数据库唯一约束处理并发重复。
- [x] 可重试错误执行可配置的有界退避；超过上限进入 DLQ 或 terminal state，不无限阻塞 partition。
- [x] 未知 schema、非法 Envelope 和明确的业务冲突直接按 non-retryable 错误隔离。
- [x] 真实 Kafka integration/TCK 验证序列化、上下文、重复投递、重试、DLQ 和消费组行为。

## Answer

Kafka Starter 提供显式三行式 imperative Consumer factory：先验证 wire contract、destination 与 schema，再建立可信 Execution Context，并由 atomic Inbox marker 与业务副作用共享唯一顶层事务。缺陷分类采用 bounded retry，non-retryable/耗尽分支交给 Kafka Binder DLQ；native codec 的 DLQ key/value serializer 由 Starter 安全配置。单元测试验证成功与异常后的 context 清理，PostgreSQL IT 验证并发 dedupe，独立 Notes consumer 在真实 Kafka 上验证重复投递、三次重试、DLQ 与消费组行为。
