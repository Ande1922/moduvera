Type: issue
Status: blocked
Blocked by: 02

# 03 — 进程内任务与线程切换隔离

## Outcome

现有进程内任务与回调在平台线程或虚拟线程上保持正确关联，实际执行的新任务拥有自己的 Span 和一次完成日志。

## Spec coverage

[正式 Spec](../spec.md)：US08、US09；ID04；TD03。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[02](./02-ecs-logging-and-context-projection.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

在已有 Snapshot/JDK 执行接缝组合业务快照、完整 OTel Context 和日志投影；区分普通回调换线程与新任务执行。提交或注册时捕获，实际同步执行期间安装，退出恢复。与相邻传播专题共享语义，按当前 checkout 复用，不引入其全部能力。

## Acceptance criteria

- [ ] 新任务提交前固定父 C/Context，父作用域退出后执行仍正确；实际开始才创建任务 Span 并计执行耗时，排队不计入执行耗时。
- [ ] 同一执行单元的普通换线程只传播完整 Context，不增加任务 Span；同步本地调用也不强制方法级埋点。
- [ ] 不同 tenant 以及同 tenant 不同 C/Actor 请求交错，实际任务内部和退出后的业务上下文、OTel、MDC 均正确；缺失状态不借用线程上的旧身份，子任务不回写父状态。
- [ ] 正常、嵌套、异常、拒绝、执行前取消和执行中取消均恢复原状态；未实际开始的任务不产出任务完成日志或虚假 Span。取消通知不能在仍执行代码时提前撤销 Scope。
- [ ] 每个实际执行并终止的任务只有一次 canonical INFO；恢复 WARN、最终 ERROR 与 safe cause/code 由真实处理责任方记录，保留原返回、异常、Future 和 Executor 关闭契约。
- [ ] 平台线程池、虚拟线程、inline/CallerRuns 与回调注册/触发均取实际内部状态证据，不依赖固定 sleep；不把 Relay poll/接管作为新的业务根。

## Verification

- [现有 Snapshot](../../../framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshot.java)
- [SnapshotTest](../../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshotTest.java)
- 使用真实 JDK Executor/ExecutorService、虚拟线程与可控 barrier/latch 的回调执行。基于 01/02 的 Agent/stdout 检查 Span 数量、父关系、canonical 次数与恢复状态，记录新增适配模块的窄测及 verify 命令。

## Exclusions

不新增调度/持久任务平台，不提升冻结的 scheduler/lock 产品状态；不重做 ExecutionContext Platform/Scope/AI 设计。独立定时或持久任务语义只保留为 Spec 约束，未支持入口不新增实现。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
