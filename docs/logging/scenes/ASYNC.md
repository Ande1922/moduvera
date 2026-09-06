# 场景：异步任务（线程池 / 协程 / 任务队列）

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。跨线程 / 协程如何传播上下文见[语言绑定](../LOGGING.md#9-语言绑定)。

- 执行单元：一次任务执行；从提交方继承 `trace_id` 与身份字段，为本任务生成新的 `span_id`
- **核心规则：先区分任务边界与纯线程切换。** 新任务是新的执行单元，不复用提交方 `span_id`；同一执行单元内的线程 / 协程切换传播完整当前上下文。接入后优先使用 SDK 的 continuation / fork 能力，不由业务代码传 span
- canonical logger：`task.execute`；**默认 INFO**。结果看 `event.outcome`
- 事件字段：`event.outcome`、`task_name`（string，稳定任务名）、`duration_ms`
- 异常最终处理点：任务包装器（未被包装的线程由全局 uncaught handler 兜底记录）
