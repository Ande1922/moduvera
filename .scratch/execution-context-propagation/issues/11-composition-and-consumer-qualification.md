Type: issue
Status: claimed
Blocked by: 04, 05, 06, 07, 09, 10

# 跨边界组合与消费者资格验收

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US21、US22；汇总 US01–US20 的既有场景证据
Test seams: T08、T09；组合复核 T01–T07

## Outcome

在已完成的场景能力之上证明少量跨边界组合真实可用，汇总独立消费者、适用拓扑回归和支持声明边界；不是最后补齐前置票遗漏实现或测试的兜底票。

## Base and scope

基于 04、05、06、07、09、10 及全部传递前置的已集成输出。使用该组合的同一已记录基线；不要混合不同源码状态的单票结果声称组合通过。前置若被 wontfix，须另行确认范围处置，不能仅因 tracker 终态就声称缺失能力已实现。

## Acceptance criteria

- [x] 逐项关联 spec 的 US01–US22/T01–T09 到实际前置票、消费者、命令及结果；缺失证据回到责任票，不在汇总中推断通过。
- [x] 在消费者公开接缝验证“可信入口→直接异步任务→业务读取”“注册回调→外部线程完成→业务读取”及“AI 请求→工具循环→流式响应”的少量组合；原生 Reactor 模板另有真实组合消费，不要求把三种方式叠在同一边界。
- [x] 组合场景覆盖不同租户、同租户不同请求、Platform 与缺失的适用分支，证明业务内部上下文正确及退出后恢复；未授权/不足上下文拒绝不能产生相关业务或消息副作用。
- [x] 每个新增公共能力已有独立消费者运行证据，可复用现有消费者/测试装配，不为 Platform 或 AI 新造正式业务服务或测试专用生产 HTTP 入口。
- [x] Kernel-only、选定 Spring、Reactor-only 与 AI 消费者的实际依赖闭包符合边界；不使用可选能力的消费者不被迫引入全部框架。与精确依赖版本/制品身份关联，不只查看 POM。
- [x] 在集成基线执行适用公共 HTTP、Local/Remote 与双拓扑 Scenario 回归；不把新增模板扩成所有基础设施组合的笛卡尔积，已有资格不能被无证据削减。
- [x] 汇总一份接入/生命周期说明：三种标准方式的分工、固定快照禁止跨请求缓存、无状态读取可共享、实际线程清理、缺失/取消/重试语义及未支持项均明确。
- [x] 核对 CONTEXT、ADR 0003/0035 与单一传播 ADR 已随前置票同步；产品支持面只按真实消费者和运行证据准确提升，本票不能把计划项或理论兼容写成 Supported。
- [x] 保留父 spec 和原 finding 的独立生命周期，不自动提交、发布、部署或关闭它们；源码、评审、gate、Scenario 证据需按仓库工作流保持固定点一致，不把本票测试当作正式最终 PASS。

## Verification

- 使用公共消费者与生产运行 Adapter；复核真实运行证据而非依赖类型名/配置存在。各前置票必须先有自己的场景测试与消费者证据，本票新增组合验证。
- 在满足运行环境且获得相应实施授权后，以 [Reference Product harness](../../../verification/reference-product/harness/verify.sh) 验证适用两种拓扑，并记录实际命令、base/head、环境、制品及测试结果。
- 运行相应 Maven 消费者、架构/依赖检查和文档链接校验；后续固定点 review/quality-gate/final-acceptance 仍按仓库阶段执行，不在本票描述中预先声称完成。
- 实际无法运行的基础设施或 Scenario 明确报告未验证，不能以旧版本的既有结果替代当前组合证据。

## Exclusions

不接手未完成前置票、不扩产品/支持矩阵、不建立新 IAM 或 Agent 业务、不添加全局 Hook，不把资格汇总当成提交/发布或自动关闭父项的授权。

## Comments

- 2026-09-05：按批准 DAG 发布汇合票；仅当各前置的实际交付与依赖满足后进入可执行前沿。

## Answer

Implementation and ticket-level qualification completed and integrated on 2026-09-06.

- Base: `04c66e84e4b05814e462ba4999759b571109e9cf`; worker head: `e055adb0148d8be69b42e2874a38641efa512e04`.
- Integrated commit: `297432f9f749c09b8d0cbc1dbd7bf3cf8fe14a5e`; full tree `3eafab29e06b9f901c07070bace13f2b1d86f538` is identical to the tested worker tree.
- Worktree: `/private/tmp/execution-context-frontier-20260905/11`; branch: `codex/execution-context-20260905-11`.
- Verification PASS: focused Notes compositions2/2; common-baseline affected consumer verification237/237; actual four dependency closures and built JAR/source identities; both public topologies and Kafka recovery at the exact worker head.
- Both independent review axes completed clean with zero findings at the same base/head.
- Mapping/commands/artifacts: [worker report](../evidence/11/worker-report.md).
- Reviews: [Standards](../evidence/11/review-standards.md), [Spec](../evidence/11/review-spec.md).
- [Combined Scenario and integrated tree identity](../evidence/11/qualification-scenario.md).

`Status: claimed` is intentionally retained solely for the separately controlled tracker lifecycle: the repository checker requires a terminal parent when every child is terminal, while this ticket requires the parent spec/finding to remain independent. There is no remaining implementation frontier and no parent closure is inferred. The full-feature final fixed-point review, Normal gate and delivery-head Scenario are separate coordinator stages; this Answer does not predeclare their PASS. Their final evidence is recorded outside committed source so it cannot invalidate the fixed point.
