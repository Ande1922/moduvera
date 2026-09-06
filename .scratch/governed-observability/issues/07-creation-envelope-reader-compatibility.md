Type: issue
Status: blocked
Blocked by: 01

# 07 — 消息读取端兼容 creation 扩展

## Outcome

新消息读取和校验端接受可选 creation Trace 扩展，同时继续接受旧无 Trace EVENT/ASYNC_COMMAND 信封，为后续写端启用提供兼容基线。

## Spec coverage

[正式 Spec](../spec.md)：US04（旧关联边界）、US17；ID02/07/08；TD06。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[01](./01-pinned-agent-runtime.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

在 MessageDescriptor 引入可选、受大小约束的协议无关纯值 carrier，并同步 mapper、两类 schema、构造夹具与 TCK。支持显式夹具往返，但现有生产调用不自动捕获/填充 creation，写端激活留给 09/10。

## Acceptance criteria

- [ ] EVENT 与 ASYNC_COMMAND 新读取端接受旧无 Trace 固定样本；新增可选 traceparent/tracestate 可显式往返，MessageType major、correlation 必填、消息身份/业务语义不变。
- [ ] 使用真实 JSON schema 校验两类新旧样本；明确旧严格 command schema（additionalProperties: false）拒绝新扩展，承诺新端读旧，不承诺新消息回退旧严格 validator。
- [ ] message-core 仅保存纯值与大小边界，无 OTel/Spring/SDK 对象、MDC、权限/身份 baggage；语义 Trace 解析由适配层标准传播器负责，不在 core 用手写正则替代 W3C。
- [ ] 仅 Trace 关系非法时放弃该关系，不改变 C，也不单凭 Trace 格式把原本合法业务消息判为 poison；旧 C 沿用现有非 blank/长度规则，不叠加 HTTP 正则。
- [ ] 原本不能进入 ExecutionContext 的旧 C 保留原值并走既有契约失败；不通过重新生成 C 掩盖非法业务关联。
- [ ] mapper、构造兼容入口、两类 envelope schema 和 Inbound TCK 一起通过；source/consumer Actor/权限归属不变，无新增 wire permissions 或同步 Service API。
- [ ] 提供 consumer-first 采用说明：升级本票读取/校验端后才启用 09/10 写出；若实际需破坏性变更，按 ADR 0020 处理，不能静默豁免。

## Verification

- [KafkaMessageMapperTest](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/KafkaMessageMapperTest.java)
- [EVENT schema](../../../framework/foundation/moduvera-message-core/src/main/resources/META-INF/moduvera-message-schemas/structured-event-envelope.schema.json)
- [COMMAND schema](../../../framework/foundation/moduvera-message-core/src/main/resources/META-INF/moduvera-message-schemas/async-command-envelope.schema.json)
- [InboundMessageContractTck](../../../framework/testing/moduvera-test-support/src/main/java/io/github/ande1922/moduvera/testing/messaging/InboundMessageContractTck.java)
- 固定新旧 JSON 样本保留来源和版本，不能仅做 mapper 对称往返。改动通用入口的负向副作用断言必须遵循消息验证工作流的 ProgressBarrier。
- `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`；先运行实际受影响的窄测试，再运行此模块组合。

## Exclusions

不主动生成生产 creation、不引入 SDK、不开始数据库迁移/发布代次/消费 Span；consumer-first 的本票是最小前置，不强制生产者等待完整处理票 08。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
- 2026-09-06：首批实施预检确认标准传播器/API 版本共享先写者由 01 拥有；维护者确认执行方案后新增直接依赖 01，先验证锁定组合，再在本票适配层使用。故事和验收范围不变。
