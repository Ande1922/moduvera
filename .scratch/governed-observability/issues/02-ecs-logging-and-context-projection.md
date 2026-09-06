Type: issue
Status: ready-for-agent
Blocked by: 01

# 02 — ECS 输出与可信上下文投影

## Outcome

业务继续使用 SLF4J，即可从公共日志组件得到规范化 ECS stdout 与可信诊断字段；同步和嵌套组合退出后恢复原状态。

## Spec coverage

[正式 Spec](../spec.md)：US01、US02（公共输出）、US07、US08（同步组合）；ID01/02/08；TD01/03。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[01](./01-pinned-agent-runtime.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

提供最小公共日志 Starter、真实 OTel Context 与可信 ExecutionContext 的日志投影，以及供各入口复用的短作用域组合接缝。BOM/依赖管理纳入实际公共组件；Kernel/message-core 继续为框架无关纯值。先采集 Boot 原生 ECS 输出，仅证实冲突时补最小 Formatter。

## Acceptance criteria

- [ ] 开发、测试与生产配置使用同一单行 ECS JSON schema；真实输出无重复键，UTC 时间/级别/logger/message/进程/服务/ECS 标识与活规范一致。
- [ ] 结构化 key-value 和 MDC 的字段名、string/number/integer 类型稳定；未知值省略而非 null、空串、假 0；有效未采样 Trace 仍有真实 trace_id/span_id，启动/库日志可无 Trace。
- [ ] tenant、Actor、Initiator 只从可信上下文投影；user_id 仅来自当前 USER Actor，认证前无业务上下文时不伪造身份，也不读取权限 baggage 建立身份。
- [ ] 单条最终 ERROR 的稳定 error.code 与安全 cause 共存、不丢字段；WARN/INFO 不带堆栈。常见 HTTP/数据库异常及嵌套 cause、message、结构化字段中的敏感哨兵不泄漏。
- [ ] 组合捕获和短作用域安装保持完整 OTel Context 与业务快照；缺失时遮蔽下层残留，正常/嵌套/异常退出恢复进入前业务上下文、OTel Scope 及本层 MDC 键，不调用全局 MDC.clear，不改变上层键。
- [ ] 普通本地调用与同一执行单元的作用域组合不新建 Span；未知 Trace 不伪造；日志级别关闭时不提前组装昂贵消息，不为日志读取或缓存正文。
- [ ] 公共组件不引入第二套 logger 门面或 SDK；Kernel 不依赖 OTel/Spring/Logback，不使用某运输的消费者不被迫引入 Servlet、Reactor、Kafka 全套依赖。

## Verification

- [HolderTest](../../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextHolderTest.java)
- [SnapshotTest](../../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshotTest.java)
- 以真实 Boot/Logback stdout 解析器检测重复键和字段类型；对照 01 锁定的 Agent 观察 ID 映射，不用手写 JSON 夹具代替 formatter 输出。新增组件的精确 Maven selector 在实现选定 artifact 后记录，先窄测再 verify；同步运行实际消费者依赖/架构检查。

## Exclusions

不将 OTel/MDC 放入 Kernel，不实施 Platform/Scope/AI 全套传播；不改变业务授权模型。各入口的 canonical 与最终错误责任由 03–06、08–13、15 实现，本票不宣称完整生命周期已合规。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。

- 2026-09-06：前置 01 经独立双轴评审及专用分支集成交付；本票解除阻塞，但不在当前 01→07 执行授权内，未认领或实施。
