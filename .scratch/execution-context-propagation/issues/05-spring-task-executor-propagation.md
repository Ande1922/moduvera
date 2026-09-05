Type: issue
Status: blocked
Blocked by: 03

# Spring TaskExecutor 接入

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US04、US08、US11、US21、US22
Test seams: T02、T08、T09

## Outcome

通过复用核心快照/绑定机制的 TaskDecorator 接入选定 Spring TaskExecutor，使直接提交与对应 @Async 调用遵守相同上下文隔离契约。

## Base and scope

基于 03；不依赖 04 的自有 JDK 装饰器。Spring 依赖在适配边界落地，实际物理归属随消费者/依赖证据确定，不为一个类型机械创建模块。

## Acceptance criteria

- [ ] TaskDecorator 在每次实际任务装饰/提交时允许缺失捕获，复用具名绑定与统一 Scope；不在 Bean 创建时捕获请求身份。
- [ ] 显式配置选定 TaskExecutor，真实 @Async 调用经过该执行器时传播完整上下文；非选定执行器不被全局劫持，也不被文档误称支持。
- [ ] Platform/Tenant/缺失以及同租户不同请求在复用线程上正确隔离；原来有身份的 inline/CallerRuns 路径退出后恢复。
- [ ] 原 Spring 任务返回、异常、拒绝、取消与生命周期行为保持；Future 包装造成的观察差异按实际公开接缝验证，不假定装饰器能捕获所有异常。
- [ ] 父 Scope 退出不自动使已提交任务失效；真实退出处清理，不能由另一线程的完成监听器清理 worker。
- [ ] 配置与 public consumer 测试证明无需 04；Kernel 不引入 Spring，使用方不被迫引入 Reactor/AI。
- [ ] 接入说明明确 TaskDecorator 与显式回调绑定的分工、@Async 实际执行器前提及状态缺失规则，随票提供消费者证据。

## Verification

- 真实 Spring ApplicationContext、TaskExecutor、TaskDecorator 与 @Async 代理；使用测试源码的最小组件，覆盖值/异常、拒绝和执行线程清理，不只调用 decorator 方法做单测。
- 在当前物理适配模块运行聚焦 test/verify，并验证消费者配置与依赖闭包；实施 Answer 记录解析后的精确模块和命令，不能把未确定 artifact 名当已存在能力。
- 以原生 Spring/JDK 执行器驱动，不引用尚未完成的 04/06/07/08。

## Exclusions

不自动装饰所有 Bean，不改变安全上下文、事务或可观测性传播，不扩展 Spring 调度产品，不保证绕过代理或其他执行器的 @Async 自动传播。

## Comments

- 2026-09-05：等待 03，与 04 无功能依赖；共享文档/POM 如有写入冲突由集成协调。
