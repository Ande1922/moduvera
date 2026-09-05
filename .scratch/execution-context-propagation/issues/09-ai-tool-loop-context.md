Type: issue
Status: resolved
Blocked by: 08

# AI 多轮工具调用隔离

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US18、US19、US21、US22
Test seams: T06、T08、T09

## Outcome

在真实 ToolCallingAdvisor 循环中按每次 ToolContext 恢复 Holder，多轮工具与最终响应使用本次请求身份；共享工具包装器不固定某一请求快照。

## Base and scope

基于 08 交付的共用请求注入、保留键契约及已选定 AI 依赖，复用其测试模型接缝与消费者。无需等待 07、自有执行器或 HTTP。

## Acceptance criteria

- [x] ToolCallback 包装器每次从传入 ToolContext 读取本次 ExecutionContext；缺失、错误类型或无上下文调用入口在执行委托前失败，不回退 Holder。
- [x] 在实际同步工具委托期间打开 Scope，正常、业务异常及框架可观察的失败路径后恢复原线程上下文；原结果及异常契约不被上下文包装改变。
- [x] 保留工具定义、名称、元数据、参数 schema 与调用结果；执行身份不进入模型输入、工具参数或工具结果。
- [x] 工具包装器不捕获请求快照；在委托可复用且线程安全时可共享，跨请求/跨租户并发时每次读取本次参数。
- [x] 真实 ToolCallingAdvisor 至少两轮工具调用；中途更换执行线程后，工具和最终响应内 Scope/Actor/Initiator/Correlation 均正确，退出后无残留。
- [x] 覆盖同租户不同请求、Platform/合法 Tenant、缺失/错误键及异常；原生上下文传到后续循环，其他调用配置和上下文键保持。
- [x] 复用 08 的消费者证明完整请求→工具循环→响应链，补工具生命周期说明与依赖回归；不为第二张 AI 票再造业务产品。
- [x] 文档明确自定义 Advisor 内部异步回调仍需显式传播，外层 return nextStream 的 Scope 不能覆盖整条流。

## Verification

- 真实 ChatClient、ToolCallingAdvisor、ToolCallback 与原生上下文路径，由测试源码脚本化 ChatModel 控制至少两轮调用，不以直接手工调用工具包装器替代框架链证据。
- 工具委托记录实际完整上下文与调用次数；缺失/错误键时调用次数为零；真实循环之后响应适配器仍观察同一执行信息。
- 对线程清理使用有界协调和原值探针，记录实际 AI 模块/消费者命令、base/head 与依赖版本。脚本测试不证明外部模型供应商行为，不使用付费调用替代测试。

## Exclusions

不增加另一套请求上下文 API、全局 Reactor Hook、工具授权产品或自定义 Agent Loop，不宣称任意内部 Advisor 全透明恢复，不扩大 08 已锁定的产品支持范围。

## Comments

- 2026-09-05：等待 08 的请求注入/原生键；与 07 无功能阻塞边。

## Answer

Implemented and integrated on 2026-09-06.

- Base: `f3ef17e028802d4072e943c167f9abea0aca5b3a`. Worker head: `c8f9669f230b0b446378eba3b9667f47d2eab093`.
- Integrated commit: `c3ac8c7a2444f51d75c8f9add995e810e3921b9e` on `codex/execution-context-20260905-integration`.
- Worktree: `/private/tmp/execution-context-frontier-20260905/09`; branch: `codex/execution-context-20260905-09`.
- Verification PASS: Original worker focused3 and affected57 tests passed. Four concurrent requests include TenantA twoidentities, TenantB and Platform; bounded first-round overlap and same actual thread restoration close independent Standards2P2 and Spec1P1 findings. Integrated Kernel52, Reactor8/consumer2, AI6/consumer8 tests passed across6modules in5.681s with normal scoped verify checks; log evidence/09/integrated-ai-reactor-verify.log. All4 ticket files and allocated ADR section match the reviewed head.
- Standards review completed clean and Spec review completed clean against the same base/worker head.
- Acceptance mapping and exact commands: [worker report](../evidence/09/worker-report.md).
- Review evidence: [Standards](../evidence/09/review-standards-closure-1.md) and [Spec](../evidence/09/review-spec-closure-1.md).
- This ticket result is not final feature acceptance; parent spec/finding remain unchanged.
