# 02 — 收敛 Broker-neutral Messaging Core

**What to build:** 将 Broker 无关的 Message Envelope、Outbox、Inbox、重试分类和 schema/version 规则整理成一个稳定的 Messaging Core，使 Kafka Starter 和业务服务只面对一套可靠性契约，消除当前没有独立发布价值的浅消息模块。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Messaging Core 同时拥有 Message Envelope、Outbox/Inbox contract、可靠性算法、重试分类和 schema/version 语义。
- [x] Core 保持 framework-free，不依赖 Spring、Spring Cloud Stream、Kafka、ORM 或业务 Command/Event。
- [x] 现有生产与测试消费者迁移到收敛后的公共 contract，迁移期间保持构建可验证。
- [x] 删除或停止发布已无独立 consumer 与验证生命周期的旧 Outbox/Inbox 浅 artifact，并同步 BOM consumer smoke。
- [x] Envelope 覆盖 message ID、type、version、source、destination、tenant、actor、original initiator、correlation、causation、occurred-at 和 payload。
- [x] In-memory 算法测试继续验证 claim/lease、published/failed、dedupe 和 transaction callback 语义，但不得被描述为 durable production adapter。
- [x] Catalog、Order、Inventory 的业务消息类型仍由对应 Service API 拥有，不进入 Messaging Core。

## Answer

Outbox/Inbox contract、内存可靠性算法和测试已迁入 `platform-message-core`，Java package seam 保持不变。两个浅 Maven artifact、root reactor/BOM 条目及 Architecture Testkit 依赖已移除，current product surface 已同步。Message Core、BOM smoke、Architecture Testkit 及其依赖测试在 JDK 26 下通过。
