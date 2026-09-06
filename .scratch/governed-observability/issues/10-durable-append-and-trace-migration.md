Type: issue
Status: blocked
Blocked by: 02, 07

# 10 — Durable 原子追加与双库迁移

## Outcome

Durable 追加将原消息、creation、初始 publication 与业务状态原子保存，双数据库历史积压保留，claim 能读出独立发布元数据。

## Spec coverage

[正式 Spec](../spec.md)：US12、US16（升级结构）；ID02/05/08；TD04/05。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[02](./02-ecs-logging-and-context-projection.md)、[07](./07-creation-envelope-reader-compatibility.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

追加 PostgreSQL/MySQL 消息迁移；扩展 intent writer、数据库映射和 claim 元数据，保持 OutboxStore 与 DurablePublication 职责分离。新增 creation/publication 的 traceparent/tracestate 可空列及非空默认 0 的 publication_generation。当前已存在 V2，实施时核对实际下一迁移版本，不修改 V1 或其他历史迁移。

## Acceptance criteria

- [ ] 正常启动继续校验，迁移执行/数据库身份初始化只由显式 App/验收策略选择；带历史行升级保留原消息、状态、失败数和积压，两库均验证。
- [ ] 正确 DataSource 上的活动可写事务内创建短 outbox.append INTERNAL Span；不可变 creation 捕获该 Context，初始 publication 引用 creation，generation=0，与业务状态及消息一起提交。
- [ ] 无事务、只读或错误 DataSource 按原契约拒绝；回滚后业务状态、消息和 Context 全部不可见，无有效发送 wake。
- [ ] append 返回/Span 结束只证明追加调用，不输出已提交业务事实或 Broker 接收成功；提交后才触发既有无载荷 wake。
- [ ] claim 返回独立 generation/publication 元数据，不把当前 publication 塞成业务身份，也不覆盖 MessageDescriptor 的 creation；已保存 creation、消息 ID/C/payload 等保持原义。
- [ ] 仅保存标准传播纯值，不序列化 SDK/MDC/权限；原 correlation 约束、tenant 隔离和消息 major 不变；库级无 Agent 不伪造 ID。
- [ ] 本票启用的新消息可选 creation 写出建立在 07 读取/校验兼容之上；旧行的未知 creation 保持未知。

## Verification

- [PublicationAdaptersTest](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/PublicationAdaptersTest.java)
- [JdbcMessagingStoreIT](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingStoreIT.java)
- [JdbcMessagingMySqlIT](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingMySqlIT.java)
- [既有 tenant 迁移测试](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/migration/MessagingTenantMigrationIT.java)
- 生产 JDBC 与真实双库验证正确/错误事务及提交/回滚；增加从旧 schema 带消息数据升级的 Trace 迁移夹具，不将已有 tenant 迁移测试当成 Trace 证明。结合 01 Agent 检查 append Span 和实际数据库记录。
- `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`；先运行实际受影响的窄测试，再运行此模块组合。

## Exclusions

不重写历史 creation、不清库、不改变迁移执行策略或可靠发布保证；本票止于追加、升级与 claim 元数据，历史准备由 11、publish 生命周期由 12、redrive 由 13 交付。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
