Type: finding
Status: confirmed
Severity: minor
Area: services
Claim: 消息 Inbound Adapter 的事务所有权已有 ADR/模块图约定，但缺少可执行架构约束来阻止 message-only Application 用例再次开启顶层事务。
Evidence: docs/implementation/MODULE-MAP.md 与 ADR 0032 都把 transaction + Inbox 交给外层 reliable endpoint；InboxTemplate 包裹 Inbox 去重与 handler，resolvePendingStock 依赖该外层事务。architecture testkit 未见对应 TransactionBoundary/inTransaction 规则。
Verification: 第二读者检查 ADR 0032、MODULE-MAP、InboxTemplate、ReliableInboundEndpoint、resolvePendingStock 与 create；再搜索 architecture testkit 是否会拒绝消息 handler 调用一个自行开启顶层事务的 Application 方法。
Planned: .scratch/message-inbound-transaction-ownership/spec.md

# 消息入站事务所有权未显式约定

## Verdict

Claim 确认成立。ADR 0032 与 MODULE-MAP 已规定可靠消息入口的外层 endpoint 拥有 transaction、Inbox 与可信上下文，`SpringTransactionBoundary` 也会在运行时拒绝嵌套顶层事务；但当前架构规则只阻止具体 Message Handler 直接拥有可靠性机制，不能在提交阶段阻止其调用的 Application 用例再次开启顶层事务。

维护者接受该 finding，但要求在独立 topic 中讨论处理方案。是否拆分 Application Service、采用方法调用图或类依赖规则、增加显式分类，以及如何修正事务边界术语均未在本轮确定。
