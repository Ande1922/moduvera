Type: issue
Status: ready-for-agent
Blocked by: 01

# 14 — 九项 Micrometer 指标运行接入

## Outcome

实际应用 registry 观察到原九项 Outbox 指标，保持原语义且不因 Agent 接入出现第二条 SDK 指标链。

## Spec coverage

[正式 Spec](../spec.md)：US19；ID09；TD08。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[01](./01-pinned-agent-runtime.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

验证并补齐真实 App registry 与既有 MicrometerPublicationObserver 的装配；缺 registry 的原可选/no-op 行为保留。此票可基于当前 Outbox 独立完成，无需等待新 Context/代次或 redrive。

## Acceptance criteria

- [ ] 保留 moduvera.messaging.outbox. 下 claimed、publish、broker.ack、stale.token、claim.conflicts、cleanup.deleted、pending、pending.oldest.seconds、terminal 九个 meter 的名称、类型、单位和现有标签。
- [ ] 真实 registry 展示 published/retry/terminal、stale/claim conflicts/cleanup、pending 数量及最旧年龄的更新；publish result 枚举与各 meter 计数来源保持原契约。
- [ ] broker.ack Timer 保留 Observer 传入 Duration 的当前语义，不借名称改成纯 ACK、端到端或排队时长。
- [ ] C、trace/span、tenant、actor/user、message、原始 URL、异常文本不作为 tags；有界路由/类型、枚举标签保留，不把日志字段表复制为标签。
- [ ] Agent metrics exporter=none，无第二 SDK/bridge 指标链；无 registry 时保持可选/no-op；不扩大 Actuator 暴露或新增公网 metrics 端点。
- [ ] 真实 App 装配与库级 registry 测试分别提供证据；以后修改消息行为的票承担相关 meter 回归，不能因本票先完成而免除。

## Verification

- [MicrometerPublicationObserverTest](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/MicrometerPublicationObserverTest.java)
- [MicrometerPublicationObserver](../../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/MicrometerPublicationObserver.java)
- 复用真实 Outbox/数据库行为驱动 App registry，测试内观察 registry 即可，不新增生产端点。以 01 有效 Agent 配置与接收端观察证明无 SDK metrics 输出；记录实际 App 选择和聚焦验证命令。
- `./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`；先运行实际受影响的窄测试，再运行此模块组合。

## Exclusions

不新建业务指标目录，不选择生产指标后端/告警/保留期，不为日志字段增加高基数标签，不改变 Observer 计时意义。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。

- 2026-09-06：前置 01 经独立双轴评审及专用分支集成交付；本票解除阻塞，但不在当前 01→07 执行授权内，未认领或实施。
