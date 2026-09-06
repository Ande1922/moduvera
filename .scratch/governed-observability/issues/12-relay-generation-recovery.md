Type: issue
Status: blocked
Blocked by: 11

# 12 — Relay 同代续投与故障恢复

## Outcome

Relay 首发、自动重试、接管及进程重启恢复当前持久代次，每次实际执行用新 Span，并分别记录发送和 Outbox 完成结果。

## Spec coverage

[正式 Spec](../spec.md)：US13、US15；ID05/06；TD04/05/07。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[11](./11-claim-owned-publication-repair.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

消费 11 的已持久 publication；在适配层通过同一 OTel SDK 包围生产发送与状态写回，message-core 继续框架无关。覆盖实际进程故障与租约窗口，不把遥测改成新的重试决策来源。

## Acceptance criteria

- [ ] 每次实际执行从数据库恢复 publication，以它为 parent 创建 outbox.publish INTERNAL Span，Link 有效 creation，范围覆盖发送和完成状态写回，退出结束。
- [ ] 首发、重试、接管、长积压、跨天和父 Span 已结束仍沿当前代 Trace；每次新 Span，不在等待期打开长 Span，不把 attempt Context 回写覆盖 creation/publication。
- [ ] Agent 负责实际 Kafka producer Span/header 注入，无同语义重复；本次发送结果与 Outbox 写回结果分别可观察，ACK 成功但写回失败不报 Outbox 已完成。
- [ ] ACK 后标记前宕机由新进程同代续投，允许原保证内重复；stale token/租约丢失不能更新状态、generation 或 Context；Inbox 原键继续去重。
- [ ] 发送异常/中断/不可观察底层重发保持原重试与计数语义；Worker 决定 retry/terminal，底层不抢先最终 ERROR，耗尽不再写将重试 WARN。
- [ ] 使用原消息 C、tenant、Actor/Initiator 语义，不被 poll/worker 管理身份覆盖；作用域与日志安全、次数、单调耗时符合规范。
- [ ] Micrometer 既有 Observer Duration 语义保留：broker.ack timer 名称不授权把传入时长改成纯 ACK 或端到端/排队时长；新日志诊断不污染 meter tags。

## Verification

- [OutboxWorker](../../../framework/foundation/moduvera-message-core/src/main/java/io/github/ande1922/moduvera/message/outbox/OutboxWorker.java)
- [JdbcMessagingStoreIT](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingStoreIT.java)
- [JdbcMessagingMySqlIT](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingMySqlIT.java)
- 生产 JDBC/Transport、真实 Kafka 与 01 OTLP 接收端联合观察数据库、record headers、Span 和日志。跨进程用停止/启动独立 Relay 的证据，重建 Java 对象不算重启。ACK→mark 窗口、写回失败、租约丢失与中断用确定性故障夹具，避免固定 sleep。
- 双库分别验证状态/claim 语义；Kafka/完整进程故障矩阵按现有支持范围选 PostgreSQL，不因 MySQL 兼容扩大其产品支持声明。Inbox 去重在现有生产入口验证，本票不依赖 08 的新消费 Span。
- `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`；先运行实际受影响的窄测试，再运行此模块组合。

## Exclusions

不创建人工新根代次、不实现管理审计历史，不改变失败上限、Inbox 键、消息保证、取消策略或授权；不将 ACK 与数据库完成合并为一个虚假成功事实。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
