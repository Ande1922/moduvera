Type: issue
Status: blocked
Blocked by: 02

# Reactor 订阅上下文模板

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US15、US16、US21、US22
Test seams: T05、T08、T09

## Outcome

通过原生 Reactor Context 传递上下文，在选定同步映射回调中恢复 Holder；调用方不依赖订阅线程或工作线程偶然带有的身份。

## Base and scope

基于 02 及 01 的显式空状态恢复。原生 Reactor 调度器即可验证，不依赖 03 的 Bound 类型、04/05 执行器或 06 HTTP。实际依赖版本、适配位置及消费者归属在本票用真实依赖闭包证明。

## Acceptance criteria

- [ ] Mono/Flux 提供可信上下文写入、调用时严格捕获传播、ContextView 严格读取、同步映射恢复接缝；两种写入方式按来源选择，不要求叠加。
- [ ] 捕获发生在传播模板调用时；订阅延迟到不同线程或父 Scope 退出之后，仍使用已固定的本次上下文，不重新读取订阅线程 Holder。
- [ ] mapInContext 按订阅读取原生 Context，每次实际同步委托期间恢复 Holder，退出后恢复原线程状态；订阅间以及同租户不同请求间隔离。
- [ ] 原生受管键缺失时，使用 01 的明确空状态遮蔽当前线程身份；键存在但类型非法时拒绝。require 不回退 Holder，业务 require/requireTenantId 在不足时失败。
- [ ] 真实跨 scheduler、并发订阅、异常、重试、取消均正确；取消通知不提前清理仍在运行的同步回调，真正退出才恢复。
- [ ] 不注册默认全局 Hook；说明任意 map/flatMap、返回 Publisher 后的内部异步工作不自动有 Holder，传播不识别 I/O 或自动调度阻塞任务。
- [ ] 文档区分可共享的无固定身份模板与禁止跨请求缓存的固定上下文 Publisher；cache/share 数据隔离不因 Context 传播而被保证。
- [ ] 公共消费者运行、选定 Reactor 版本兼容和依赖闭包检查随票完成；Kernel 消费者不被迫依赖 Reactor，Reactor-only 消费者不被迫引入 AI。

## Verification

- 使用真实 Reactor 订阅、操作符和 scheduler，公开模板为主接缝；测试线程预装不同身份后验证缺键遮蔽及退出恢复，不能只测 Context map 存取。
- 采用有界协调控制重试、取消及并发顺序，断言 Scope/Actor/Initiator/Correlation；保留纯原生操作符不自动恢复 Holder 的明确支持边界。
- 在实际 Reactor 适配模块和独立消费者运行聚焦测试，记录精确 Maven 模块/版本/命令与缺失依赖检查；不引用未完成 AI/Executor 票作为通过前提。

## Exclusions

不做全局 Reactor/Micrometer 平台，不实现 WebFlux 安全入口或所有操作符透明传播，不处理共享业务数据缓存的租户分区，也不搬运任意 ThreadLocal。

## Comments

- 2026-09-05：等待 02；AI 的原生上下文通道不以本票为前置。
