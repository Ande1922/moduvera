Type: finding
Status: confirmed
Severity: minor
Area: framework
Claim: ReliableMessageConsumerFactory、ReliableInboundEndpoint 与 InboxTemplate 的创建关系在代码中一致，但命名和文档没有准确区分装配者、运行时入口与直接事务所有者，容易让维护者误判消息事务从哪里开始。
Evidence: AutoConfiguration 只发布 ReliableMessageConsumerFactory；业务 InboundConfiguration 注入该 Factory 并把 `forConsumer(...)` 返回的 ReliableInboundEndpoint 注册为 Spring Consumer。Factory 创建每个 consumer 专属的 InboxTemplate 与 Endpoint；Endpoint 负责 Spring Message mapping、contract 校验、trusted ExecutionContext 和 bounded retry，而 `InboxTemplate.handle(...)` 才直接调用 `TransactionBoundary.inTransaction(...)`。ADR 0032 与 MODULE-MAP 使用“Reliable Endpoint owns transaction/Inbox”的整体表述，没有细分三者职责。
Verification: 读取 ModuveraMessagingKafkaAutoConfiguration、ReliableMessageConsumerFactory、ReliableInboundEndpoint、InboxTemplate，以及 Order、Inventory 与 Notes 的 InboundConfiguration，列出每一层的构造依赖、运行时职责和直接调用边界。
Planned: .scratch/reliable-inbound-api-clarity/spec.md

# 可靠消息入站三层职责不够明确

## Verdict

Claim 确认成立。Factory 与 Endpoint 并非互斥实现：Factory 是共享依赖和策略的装配入口，Endpoint 是每个业务 consumer 的运行时 Spring Consumer，InboxTemplate 是直接打开事务并把 Inbox admission 与业务回调原子组合的模板。现有代码在三个消费者中使用一致，但命名与文档把整体责任简化为 Endpoint ownership，导致事务调用链需要深入实现才能辨认。

维护者接受该问题并要求明确为什么有的调用点使用 Factory、有的类型是 Endpoint。具体采用重命名、收敛层次、公开组合 API 或仅修正文档，放入独立的 `reliable-inbound-api-clarity` 专题讨论。

Disposition: 接受但降低修复优先级；2026-09-03 本轮不讨论处理方案。该问题拆为独立专题，不阻塞 finding 06 的事务所有权门禁，也不在尚未确认设计前实施重命名或 API 调整。
