Type: issue
Status: ready-for-agent
Blocked by: 02, 07

# 09 — Immediate 一次 ACK 发布诊断

## Outcome

ImmediatePublication 在事务外一次发送中保留 C 与实际 Trace，并以可观察 Broker ACK 判断结果，事务内仍拒绝且零发送。

## Spec coverage

[正式 Spec](../spec.md)：US11、US10（Kafka 出站）；ID02/05/07；TD04/07。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[02](./02-ecs-logging-and-context-projection.md)、[07](./07-creation-envelope-reader-compatibility.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

在 KafkaImmediatePublication/MessageTransport 现有一次同步 ACK 路径接入可选 creation 捕获与规范结果记录。creation 纯值由实际上下文得出，transport header/producer Span 继续归 Agent；采用 07 已升级的读取端。

## Acceptance criteria

- [ ] 事务外发布继承已有 C/实际当前 Context，可选 creation 写出与本次 transport header 分层；无有效 Context 时不伪造 Trace。
- [ ] 返回成功仍以配置的真实 Broker ACK 为界；失败保留原异常和保证，发送失败不证明 Broker/远端业务一定未收到或未执行。
- [ ] 任何数据库事务活动时在发送前拒绝，真实 transport 零发送，不产生虚假 ACK 成功日志。
- [ ] Agent 拥有 producer Span 与标准注入；框架不复制同语义 Span，不增加自动重试/持久化/人工 redrive。
- [ ] 应记录的业务边界尝试有结果和单调耗时，级别/最终处理责任按活规范；内部调用不因使用 Kafka 一律强制成功 INFO。cause/headers/body 安全，不能编造不可观察底层重发次数。

## Verification

- [PublicationAdaptersTest](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/PublicationAdaptersTest.java)
- [KafkaImmediatePublication](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/KafkaImmediatePublication.java)
- 通过真实 Kafka 的 ACK、失败/超时夹具及真实事务边界验证；逐层保留 envelope creation、record headers、producer Span 与实际发送数量。无需等待 08，07 已提供最小 consumer-first 读取能力。
- `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`；先运行实际受影响的窄测试，再运行此模块组合。

## Exclusions

不获得 Durable/Outbox 语义，不改变发布保证、异常契约或 MessageId 规则；不承担 Relay 自动重试。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。

- 2026-09-06：前置 02 已经独立双轴评审并快进集成，直接前置均已交付，本票解除阻塞；不在第二批 02/14 的实施范围内，未认领或开始。
