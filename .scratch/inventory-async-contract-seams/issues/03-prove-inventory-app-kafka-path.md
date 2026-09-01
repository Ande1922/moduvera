# 03 — 通过真实 Kafka 验证独立 Inventory App

**What to build:** 让独立 Inventory App 通过真实 Kafka 完整证明 Reserve Inventory Command 的可信消费、业务执行、结果发布和重复投递安全，而不是依赖类之间的转发断言。

**Blocked by:** 02 — 让库存预占消息直接调用 Application Service

**Status:** ready-for-agent

- [ ] 有效的 Reserve Inventory Command 经真实 Kafka 被接受，产生正确的 Inventory Reserved 或 Inventory Rejected 结果，并保持库存全成或全拒。
- [ ] 可信 Execution Context 使用消费端固定的最小 `inventory:reserve` 权限，并正确传播 Tenant、Initiator 与 Correlation；wire Actor 的权限声明不能扩大当前执行权限。
- [ ] 重复投递只产生一次业务效果和一次结果发布；错误 kind、type、source 或 destination 在 Application 行为前被拒绝。
- [ ] Inventory App 的聚焦集成验证通过，并保留 Inbox/Outbox 原子性与失败重试证据。
