Type: issue
Status: resolved
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

- [x] 开发、测试与生产配置使用同一单行 ECS JSON schema；真实输出无重复键，UTC 时间/级别/logger/message/进程/服务/ECS 标识与活规范一致。
- [x] 结构化 key-value 和 MDC 的字段名、string/number/integer 类型稳定；未知值省略而非 null、空串、假 0；有效未采样 Trace 仍有真实 trace_id/span_id，启动/库日志可无 Trace。
- [x] tenant、Actor、Initiator 只从可信上下文投影；user_id 仅来自当前 USER Actor，认证前无业务上下文时不伪造身份，也不读取权限 baggage 建立身份。
- [x] 单条最终 ERROR 的稳定 error.code 与安全 cause 共存、不丢字段；WARN/INFO 不带堆栈。常见 HTTP/数据库异常及嵌套 cause、message、结构化字段中的敏感哨兵不泄漏。
- [x] 组合捕获和短作用域安装保持完整 OTel Context 与业务快照；缺失时遮蔽下层残留，正常/嵌套/异常退出恢复进入前业务上下文、OTel Scope 及本层 MDC 键，不调用全局 MDC.clear，不改变上层键。
- [x] 普通本地调用与同一执行单元的作用域组合不新建 Span；未知 Trace 不伪造；日志级别关闭时不提前组装昂贵消息，不为日志读取或缓存正文。
- [x] 公共组件不引入第二套 logger 门面或 SDK；Kernel 不依赖 OTel/Spring/Logback，不使用某运输的消费者不被迫引入 Servlet、Reactor、Kafka 全套依赖。

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

- 2026-09-06：维护者再次调用 implement-frontier 继续实施，第二批推进 02、14。本票由 `/root/wave2_worker02_logging` 在独立 worktree 认领，实际 base 为 `70e9a2711c56835ea28347fa2683618514b3bd08`；正在实现，尚无评审或交付通过结论。


## Answer

- 实际 base：`70e9a2711c56835ea28347fa2683618514b3bd08`；实现 `f5273f632ee012557d96046b0e38df54dbcfc12b`，普通修复 `0a782718441456ef0223115b7acde7d5583355a9`、`19f8022f3b5efa003bd6f7cade7bd99c09203b45`。原 writer、分支与工作区见执行台账。
- 真实 Boot 4.1.1 原生 ECS 复现 `error.code` 与 cause 冲突后，补最小公共 Logging Starter。保留完整 OTel Context、可信身份及短作用域恢复；无 Kernel OTel、第二 SDK 或 logger 门面。
- 最终代码在上述 base..`19f8022f3b5efa003bd6f7cade7bd99c09203b45` 经独立 Standards、Spec 双轴审查均 CLEAN，报告位于 `/private/tmp/governed-observability-wave2-20260906/evidence/02/review-round2-repair-standards.md` 和 `review-round2-repair-spec.md`。原隐私遗漏有 RED/GREEN 和两轮修复历史；异步/自定义 appender 不是本票已验证的支持面，当前限定 Boot 同步控制台，不声称已修复或支持异步管线。
- `./mvnw -Dmaven.repo.local=/private/tmp/governed-observability-frontier-20260906/m2 -pl framework/starters/moduvera-logging-spring-boot-starter -am verify` 通过 Kernel 52 与 Starter 18 tests、Spotless、PMD、JaCoCo；人工 Clean Code 已完成。最终真实 Agent 2.31.1/API 1.65.0 夹具输出 6 条 ECS、1 个 SERVER Span，覆盖 sampled/unsampled/absent，7 组随机敏感哨兵在 stdout/stderr/原始 Span 中均无匹配。
- 已无冲突快进集成；集成后 `./mvnw -Dmaven.repo.local=/private/tmp/governed-observability-frontier-20260906/m2 -pl framework/testing/moduvera-bom-smoke -am test` 的 27 模块全部通过。首轮因沙箱拒绝本地测试端口绑定而失败，自动审批后原命令重跑通过；两份输出和实际退出码分别保留为 `integration-bom-smoke` 与 `integration-bom-smoke-approved`。
- 当前证据索引：`/private/tmp/governed-observability-wave2-20260906/evidence/execution-state.json`；逐条准则与命令映射在该票原 worker report 和 `review-round2-worker-repair.md`。本票交付不等于本批最终 PASS；14、最终聚合双轴审查、Normal Gate 与适用 Scenario 仍须继续。本票不扩大后续入口生命周期、异步 appender 或父 Spec 的完成范围。


- 后续聚合在 `6a3e9f06d9b0c8d0eeede0d0da65b50694305deb` 发现 STDS-W2-001：camel/compact credential 字段可绕过过滤。原 02 writer 在原工作区追加普通修复 `f7926bfd9d738a82c112187118fdfac4a107adae`、`f702bc9d364f855f6f1d7e93562bd9d65ae861fe`、`aacd487c91f347116fa8e8f129a47516e6a90d6c`。修复前 RED 分别保留原八条泄漏、长消息/普通字段回归，以及 API-key/数字后缀别名遗漏；前两版补丁未单独提前集成，失败回执均保留。
- 最终实现使用单向赋值扫描、分隔符/驼峰/数字词边界，并保留既有 API-key 专用规范化匹配。修复保留普通字段、完整转义引号/Basic/Bearer 值、安全 ID/大小字段，无公共 API、依赖或管线变更。
- 最终 formatter 13、Kernel 52、Starter 22 tests，Spotless/PMD/JaCoCo、原始探针及当前 extension/fixture package 均通过。实际 Agent 2.31.1/API 1.65.0 回放的十组随机哨兵在 stdout/stderr/原始 Span 中零匹配，含新增 apiKey2；14 的受控 metrics 观察保持 Trace 1、metrics 0、logs 0、500 ms 周期和两秒窗口。
- 修复证据：`/private/tmp/governed-observability-wave2-20260906/evidence/02/aggregate-repair-round3-worker-report.md`、`aggregate-repair-round3-preflight-current-clean.log/.exit`；原票双轴复审：在 B0..aacd487 上均 CLEAN，原作者分别关闭各自发现，报告为 `aggregate-repair-round3-review-standards.md` 与 `aggregate-repair-round3-review-spec.md`。聚合作者复审、Normal Gate 和最终当前提交的运行回放仍须继续，旧最终证据只属于原提交。
- 上述最终修复已快进纳入隔离 integration，无冲突；集成后 Logging/Messaging Starter 的 `-am test` 通过，回执为 `/private/tmp/governed-observability-wave2-20260906/evidence/integration-repair-cross-check.out/.exit`。

- 后续正式 Normal Gate 在 `76059639bc93cd95ca1be0e7aef13fb8dd663a0d` 的 sensitive-content 阶段失败；95 项门禁自测及 diff/链接/Skill 检查通过，Maven 未运行。正式失败证据保留在 `/private/tmp/governed-observability-wave2-20260906/integration/.quality-gate/runs/20260906T153925.077469Z-56305`，未跳过或削弱扫描。
- 原 writer 追加普通提交 `cae819b30c58b172bb82694256afe863e7c4b670`，仅调整三处测试/夹具的动态输入表达式。生产 formatter、规则及断言均不变；保留十类随机哨兵、原查询/转义引号和 Basic 输入覆盖。原 Standards/Spec 在 B0..cae819b 均 CLEAN；报告为 `/private/tmp/governed-observability-wave2-20260906/evidence/02/gate-fix-worker-report.md`、`gate-fix-review-standards.md`、`gate-fix-review-spec.md`。
- 完整 B0..cae819b 独立敏感检查、聚焦/模块测试、静态契约和实际 Agent 运行均通过，真实干净工作区预检 PASS。补丁已无冲突快进集成，集成敏感检查也通过；回执 `integration-gate-fix-sensitive.out/.exit`。最终新提交的聚合双轴与完整 Normal Gate 仍须完成，单独敏感检查不替代正式 Gate。
