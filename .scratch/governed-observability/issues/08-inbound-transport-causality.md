Type: issue
Status: blocked
Blocked by: 02, 07

# 08 — 逐条消费保留本次投递因果

## Outcome

每条消息的 Inbox/handler 处理沿本次有效 transport 因果关系执行，creation 仅补充 Link，重试、批次与重复投递保持隔离。

## Spec coverage

[正式 Spec](../spec.md)：US18、US15（消费去重）；ID02/07；TD07。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[02](./02-ecs-logging-and-context-projection.md)、[07](./07-creation-envelope-reader-compatibility.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

在现有 ReliableInboundEndpoint 和 Inbox/handler 边界组合可信上下文与 Agent 当前 Context，保留原 validation→身份建立→消费重试顺序。真实 Kafka/导出夹具显式提供不同 creation/transport，即可在 redrive 尚未实现时独立验证。

## Acceptance criteria

- [ ] 保留 Agent 当前有效处理 Context；每次 Inbox/handler 或局部业务重试使用适当 INTERNAL 子 Span，creation 仅作 Link，不覆盖本次父关系。
- [ ] 缺少传入 parent 时复用 Agent 已建有效根；仅当前也无有效 Context 时补根处理 Span 并 Link 有效 creation，不复制同语义 consumer Span。parent 或 Links 的具体形状以锁定 Agent 实际输出为准。
- [ ] 逐条恢复原 C、可信 tenant、本地 consumer Actor、适用 Initiator 和本地权限；不同 tenant/同 tenant 不同请求及 poll 批次不借第一条消息的身份或 Trace。
- [ ] 成功、失败、retry、取消/异常后恢复原业务/OTel/MDC 状态；每个实际处理尝试的 canonical、恢复 WARN、最终 ERROR 的责任/次数符合活规范，重试次数来自真正控制器。
- [ ] 同 MessageId 的 DUPLICATE 不再次调用业务；不改变 Inbox 去重键、producer 认证、destination ACL、consumer 权限、消费事务和重试归属。
- [ ] 真实 transport headers 与信封 creation 分别留证，非法 Trace 不独立 poison；敏感内容不从日志/cause/自动采集泄漏，Span 因果和数量均正确。

## Verification

- [ReliableInboundEndpointTest](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/ReliableInboundEndpointTest.java)
- [真实 endpoint](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/ReliableInboundEndpoint.java)
- [Inbound TCK](../../../framework/testing/moduvera-test-support/src/main/java/io/github/ande1922/moduvera/testing/messaging/InboundMessageContractTck.java)
- 使用生产 StreamBridgeMessageTransport→真实 Kafka→ReliableInboundEndpoint、真实 JDBC Inbox 与 01 导出端。发送前取 ProgressBarrier，消费进度确认后才断言无业务副作用；重试/恢复同时核对 C/Trace/Actor/Initiator/tenant。人工 redrive 的最终拼接由 13 补证，不作为本票前置。
- `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`；先运行实际受影响的窄测试，再运行此模块组合。

## Exclusions

不改消息权限或消费事务归属，不新增批量 handler/虚假共同父节点，不以 creation 重置当前 consumer Trace；不承担人工恢复 API。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
