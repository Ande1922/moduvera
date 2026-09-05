Type: issue
Status: claimed
Blocked by: 02

# 请求归属的具名回调绑定

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US03、US04、US09、US10、US11、US22
Test seams: T01、T03、T08、T09

## Outcome

为请求归属的任务和回调提供具名绑定对象，固定注册时的执行上下文；普通业务函数仍可复用，异步完成线程不会成为错误的身份来源。

## Base and scope

基于 02 的三态模型及 01 恢复内核；完整测试覆盖 Platform/Tenant/缺失。仅扩展 Snapshot 绑定面和消费者示例，不依赖 04/05 的执行器装饰。

## Acceptance criteria

- [ ] 提供 Runnable、Callable、Supplier、Function、Consumer、BiFunction、BiConsumer 的具名 Bound 类型，兼容对应 JDK 接口，构造经 Snapshot 绑定入口完成。
- [ ] 绑定期间固定快照，委托执行时开 Scope 并恢复；值与原异常透明，允许缺失快照遮蔽目标线程旧身份。
- [ ] Future 已完成时 inline 回调、由外部线程完成及使用原生指定 Executor 的异步回调均读注册方身份；业务严格捕获缺失时立即失败。
- [ ] 父 Scope 退出后回调仍可执行；同次执行可重试/重复回调；子范围切换不回写父范围。覆盖同租户不同 Actor/Initiator/Correlation。
- [ ] 用独立业务函数复用示例与 Javadoc 明确禁止跨请求缓存 Bound 对象，即使 Tenant 相同；不承诺运行时检测所有类型擦除/缓存误用或将其视为一次性对象。
- [ ] thenCompose 只覆盖同步回调，内部新异步链需独立传播；长期监听器按每条可信事件建立，不固定注册监听器时的请求身份。提供两种边界的可运行测试/示例。
- [ ] 保留 Runnable/Callable wrap 兼容入口，委托统一机制；直接调用原函数，避免在每次回调内部再创建捕获 lambda；不承诺零分配。
- [ ] 公开消费者调用、类型/生命周期说明与 Kernel 依赖检查随本票交付，不等待 11。

## Verification

- Kernel 公开绑定 API＋真实 CompletableFuture/CompletionStage 覆盖七类形状、同步/异步触发、失败、重复执行及空状态恢复。
- SDK 形状的测试驱动只模拟注册/触发边界，不声称兼容所有供应商；长期监听示例从测试提供的可信事件入口信息逐次重建。
- 使用原生 JDK 执行器和有界 latch/barrier，保证在 04/05 不存在时可验收；首个命令为 `./mvnw -pl framework/foundation/moduvera-kernel -am test`。

## Exclusions

不全局拦截 Future/commonPool，不新增执行器装饰或任意函数接口生成框架，不实现自动缓存归属检测，不扩展 SDK 内部派生异步工作的支持范围。

## Comments

- 2026-09-05：按批准 DAG 等待 02；03 交付的具名绑定是 04/05 的直接前置。
