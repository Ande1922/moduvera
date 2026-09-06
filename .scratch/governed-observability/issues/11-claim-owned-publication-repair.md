Type: issue
Status: blocked
Blocked by: 10

# 11 — 历史 publication 先存后发

## Outcome

Relay 对缺失或损坏 publication 的历史消息，在有效 claim 下先持久保存可复用 Context，成功后才允许发送，避免临时 Trace 随每次处理变化。

## Spec coverage

[正式 Spec](../spec.md)：US16；ID06/08；TD05。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[10](./10-durable-append-and-trace-migration.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

实现 claim token 保护的 publication 准备/修复能力，并接入现有 worker 的实际发送前路径。此票拥有准备、持久化和准入顺序；12 拥有准备结果的 OTel 执行恢复与完整 publish Span/故障生命周期。

## Acceptance criteria

- [ ] 缺 creation/publication 的旧行保持 creation 历史未知；有效 claim 下初始化 publication 并提交成功后才发送，后续读取/准备复用同一已保存值。
- [ ] 使用标准传播器识别损坏 Context，丢弃非法父关系；有效 claim 下修复并先持久化，generation 不增加，WARN 明示连续性中断。
- [ ] stale token、并发失去 claim、准备写入失败或回滚不能覆盖有效状态/Context，不能继续用仅在内存的临时 Context 发送；保留原取消/重试处置。
- [ ] CAS 只更新允许的 publication 元数据，不改变 creation、C、消息/command/causation ID、payload、tenant、路由或累计失败数。
- [ ] 生产路径确实调用准备接缝，证明 store 成功先于 transport.send；不能只交付未被调用的方法，不能靠管理线程、闭包或缓存找回修复结果。
- [ ] 无 Agent/有效 SDK 的隔离测试不伪造 Trace；受治理路径依赖 01 的真实装载。修复提交与后续读取取数据库证据，正常与异常退出 Scope 均恢复。

## Verification

- [JdbcMessagingStoreIT](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingStoreIT.java)
- [JdbcMessagingMySqlIT](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingMySqlIT.java)
- [OutboxWorker](../../../framework/foundation/moduvera-message-core/src/main/java/io/github/ande1922/moduvera/message/outbox/OutboxWorker.java)
- 双数据库生产 Adapter 验证缺失/损坏、重复准备、stale、并发和事务回滚。真实 before-send 路径在发送时读取已提交 publication，持久化失败则断言零发送；不以 mocked CAS 证明原子性。完整进程重启与 publish Trace 连续性在 12。
- `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`；先运行实际受影响的窄测试，再运行此模块组合。

## Exclusions

不新建人工发布代次、不伪造历史 creation；不提前实现 12 的完整 publish Span/ACK 后宕机矩阵，不改变既有 Outbox 可靠交付语义。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
