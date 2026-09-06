Type: issue
Status: blocked
Blocked by: 12, 08

# 13 — 人工 redrive 原子接受新代

## Outcome

人工恢复在既有 terminal/token CAS 接受点原子保存新代与新根 Trace；后续自动重试和消费保留新的投递因果及原消息身份。

## Spec coverage

[正式 Spec](../spec.md)：US14、US15、US18（人工重投整链）；ID06/07；TD04/05/07。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[12](./12-relay-generation-recovery.md)、[08](./08-inbound-transport-causality.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

扩展 OutboxAdministration/JDBC redrive 原子更新，在同一事务内接受新 publication；结合 08 和 12 验证数据库、独立 Relay、真实 Kafka 消费的完整恢复关系。

## Acceptance criteria

- [ ] 仅 message_id + TERMINAL + claim_token CAS 成功时，原子完成 TERMINAL→PENDING、generation+1、新无 parent 根锚点 Context 保存及原调度/claim/失败状态清理，累计失败计数保留。
- [ ] 新根 Link 有效 creation，可附管理请求 Link；原消息 C 不被管理请求 C 覆盖。根 Span 在数据库决定完成后结束，不等待最终投递。
- [ ] 并发失败、旧 token 或事务回滚不产生有效新代，状态与 Context 同回滚；成功提交只发原无载荷 wake，Relay 仅凭数据库可恢复新代。
- [ ] 连续两次成功人工恢复形成不同的新根代次，T2/T3 各代自动重试和重启保持自身 Trace；creation、MessageId、C、causation、payload、原始时间、tenant、source、destination、partition key 不变。
- [ ] redrive 提交后、根 Span 导出前宕机，新进程仍恢复持久新代；每次 publish Link creation，不承诺未被接收端收到的根 Span 已可查询。
- [ ] 真实 record transport 因果与信封 creation 分层；消费不被旧 creation 拉回旧父链，08 的 parent/Links 及数量规则保留。
- [ ] 已成功消费者面对原 MessageId 仍 DUPLICATE、不再次执行业务；原失败计数、租约、事务、ACK/写回结果及安全/指标语义不变。

## Verification

- [JdbcOutboxStore](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/JdbcOutboxStore.java)
- [JdbcMessagingStoreIT](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingStoreIT.java)
- [JdbcMessagingMySqlIT](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingMySqlIT.java)
- [ReliableInboundEndpointTest](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/ReliableInboundEndpointTest.java)
- 双数据库以生产 Adapter 证明 CAS、并发、回滚与字段不变量；真实 Kafka/独立 Relay/OTLP 证明两次 redrive、提交后导出前宕机、新代续投及消费去重。先取 ProgressBarrier 再发，确认消费进度后检查未重复业务副作用。
- `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`；先运行实际受影响的窄测试，再运行此模块组合。

## Exclusions

不改变 MessageId 或强制已成功消费者重跑，不建设管理操作历史审计，不改变恢复 API 的身份/权限或既有 CAS 接受点。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
