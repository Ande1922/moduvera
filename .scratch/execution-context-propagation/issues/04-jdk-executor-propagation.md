Type: issue
Status: claimed
Blocked by: 03

# JDK 执行器传播

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US04、US07、US08、US09、US11、US21、US22
Test seams: T02、T03、T08、T09

## Outcome

装饰选定 Executor/ExecutorService，在直接提交时捕获存在或缺失状态；平台线程池、每任务虚拟线程与 inline 执行获得一致隔离，原执行器生命周期语义保持。

## Base and scope

基于 03 的具名绑定与其前置；JDK-only 接缝留在 Kernel context 边界。独立于 Spring、HTTP、Reactor 和 AI。

## Acceptance criteria

- [ ] 提供显式 Executor/ExecutorService 装饰，每次直接提交使用允许缺失捕获；创建装饰器时不保存请求身份。
- [ ] 真实单工作线程连续处理不同租户、同租户不同请求、Platform 与缺失，任务内身份正确且退出后恢复原状态。
- [ ] 每任务虚拟线程、inline Executor 与 CallerRuns 使用同一恢复语义，不依赖继承 ThreadLocal，不把 carrier 切换当作新任务。
- [ ] execute、submit、适用批量调用的值、异常、拒绝和 Future 行为与委托契约一致；上下文包装不新增任务生命周期所有权。
- [ ] 覆盖任务开始前取消、运行中取消、超时及忽略/延后响应中断的工作；通知发生时不清理另一线程，实际退出后清理。
- [ ] shutdown、shutdownNow、awaitTermination、close 与待执行任务处理遵循原 ExecutorService 契约；记录真实委托策略及验证，不隐式接管其他执行器。
- [ ] 显式 Bound 回调再经过执行器装饰时使用其原绑定身份；FutureTask 等嵌套安全，不反射拆解，也不承诺恰好一层包装。
- [ ] 提供公共消费者用法，说明“直接任务提交时捕获”不能替代“Future 回调注册时绑定”；依赖和生命周期说明随票完成。

## Verification

- 真实 JDK ExecutorService、ThreadPoolExecutor、虚拟线程执行器与 inline 实现；有界同步协调证明取消前/后及真正退出，而非固定 sleep。
- 同一业务任务分别走原委托/装饰器对照返回、异常、拒绝及生命周期结果；取消后在同一复用 worker 观察原状态。
- 运行 Kernel 聚焦测试及实际消费者编译/架构检查；记录 `./mvnw -pl framework/foundation/moduvera-kernel -am test` 与风险所需扩展结果。

## Exclusions

不全局接管 raw Thread/commonPool，不增加调度器产品或 Spring 装饰，不为取消回调开放跨线程 clear，不将所有 Future 内部异步工作宣称为自动覆盖。

## Comments

- 2026-09-05：等待 03；无需等待或顺带实现 05。
