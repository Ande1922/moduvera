Type: issue
Status: resolved
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

- [x] 保留 moduvera.messaging.outbox. 下 claimed、publish、broker.ack、stale.token、claim.conflicts、cleanup.deleted、pending、pending.oldest.seconds、terminal 九个 meter 的名称、类型、单位和现有标签。
- [x] 真实 registry 展示 published/retry/terminal、stale/claim conflicts/cleanup、pending 数量及最旧年龄的更新；publish result 枚举与各 meter 计数来源保持原契约。
- [x] broker.ack Timer 保留 Observer 传入 Duration 的当前语义，不借名称改成纯 ACK、端到端或排队时长。
- [x] C、trace/span、tenant、actor/user、message、原始 URL、异常文本不作为 tags；有界路由/类型、枚举标签保留，不把日志字段表复制为标签。
- [x] Agent metrics exporter=none，无第二 SDK/bridge 指标链；无 registry 时保持可选/no-op；不扩大 Actuator 暴露或新增公网 metrics 端点。
- [x] 真实 App 装配与库级 registry 测试分别提供证据；以后修改消息行为的票承担相关 meter 回归，不能因本票先完成而免除。

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

- 2026-09-06：按维护者第二批 02/14 继续实施授权认领；工作区将在记录 02 交付与本次状态的普通集成提交后创建，实际 base 由 Git 解析并写入外部执行索引。前置 01 已在当前集成历史中，02 先行仅为共享验证资产及快进顺序；当前尚无本票实现或验证通过结论。


## Answer

- 实际 base：`6f79a547dbf0c664b79a6c56ab280144e8d281d3`；实现提交 `4737c9fbb0bcce1d0ec797329083008f5ca95ddd`，普通验证修复提交 `a9f2b4a28c394987218117c520310f8433c8a9de`。原 writer `/root/wave2_worker14_metrics`，工作区 `/private/tmp/governed-observability-wave2-20260906/14`，分支 `codex/governed-observability-wave2-20260906-14`。01 交付提交在实际 base 的祖先链上。
- 已有生产装配满足要求，改动仅涉及三个 Java 测试和三处验证资产。库级断言覆盖九项名称、类型、单位、现有有限标签、publish result 与传入 Duration 的原语义；缺 registry 的 no-op 与存在 registry 的 Observer 选择分别验证。没有新增 SDK/bridge、生产依赖、管理端点或 Actuator 暴露。
- 真实 Order App 的 registry 由生产 OutboxWorker、JdbcOutboxStore、OutboxMaintenance 与真实 PostgreSQL/Kafka 路径驱动，验证 published/retry/terminal、stale token、行锁 claim conflict、cleanup 和 pending/age/terminal backlog。重试使用测试传输故障，stale 在测试中替换 token；数据库状态转换仍走生产 Adapter。库级回调测试与真实 App 证据分开保留。
- Agent 验证保留 metrics exporter=none，并把潜在 metrics 请求指向当前接收端 `/v1/metrics`，固定 http/protobuf 与 500 ms 导出周期。真实 Trace 到达后观察两秒（四个周期长度），记录 traces=1、metrics=0；实际 JVM 启动参数与回执一致。这是本次受控 JVM 的有限观察，不是未来配置或生产指标后端资格。
- 初次两轴均发现 metrics 端点/观察周期证据不足；原 writer 只修复验证脚本。原 Standards `/root/wave2_review14_standards` 与 Spec `/root/wave2_review14_spec` 对上述完整替换比较点均 CLEAN、零未关闭发现；各自关闭 STDS-14-001 / SPEC-14-001。请求 Sol/high/fresh，有效设置未暴露，记未验证。
- 原始命令、失败尝试、清理复查和报告在 `/private/tmp/governed-observability-wave2-20260906/evidence/14/`：`worker-report.md`、`worker-review-repair-round1.md`、`review-round2-standards.md`、`review-round2-spec.md`。规定 messaging starter 组合 verify 通过，其中 Kafka starter 模块 58 项单元测试与 36 项集成测试通过；真实 App 选定 IT 1 test 通过；最终 receiver 3 tests、Agent 静态契约与真实 Agent 日志/指标验证通过。当前干净预检为 `28-review-repair-preflight.out/.exit`，fingerprint `32a2e9a41f534da3065522623d0ad9bdb37ec4333c4fae47d8c0389f9f59710f`。
- 已无冲突快进整合到第二批 integration，logging 与 messaging starter 组合 test 通过，回执为 `integration-cross-ticket-test.out/.exit`。本批最终聚合 Review、Normal Gate 和适用 Scenario 在最终 tracker 提交冻结后外置记录；本票完成不豁免后续消息行为票的 meter 回归，也不关闭父 Spec。
