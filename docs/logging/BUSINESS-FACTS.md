# 参考业务事实登记

本登记遵循 [日志规范](LOGGING.md) 与 [Java 绑定](bindings/JAVA-SPRING-BOOT.md)，
只覆盖现有 Order / Inventory 用例，不增加业务流程或审计保证。

| 稳定 action / code | 成立条件 | 唯一记录责任方 | 业务键 |
| --- | --- | --- | --- |
| `order_created` | 订单与预留库存 Outbox 意图在同一本地事务提交成功 | `OrderApplicationService.create` | `order_id` number |
| `inventory_reserved` | 首次库存预留决定、库存变更、结果 Outbox 意图与 Inbox 同事务提交成功 | `InventoryReservationHandler.handle` | `order_id` number |
| `BIZ_INVENTORY_STOCK_UNAVAILABLE`（error.code） | 首次库存不足拒绝决定、结果 Outbox 意图与 Inbox 同事务提交成功 | `InventoryReservationHandler.handle` | `order_id` number |
| `order_confirmed` | 订单从 PENDING_STOCK 变为 CONFIRMED，且更新与 Inbox 同事务提交成功 | `InventoryResultHandler.handle` | `order_id` number |
| `order_rejected` | 订单从 PENDING_STOCK 变为 REJECTED，且更新与 Inbox 同事务提交成功 | `InventoryResultHandler.handle` | `order_id` number |

以上均为 INFO，不带 cause 或堆栈。库存预期拒绝只带 BIZ code；订单拒绝日志表示另一个
已提交的订单状态变化。无新决定的 command 重放、Inbox 重投、已处于相同终态的结果、
纯查询和订单金额计算不产生这些记录。Outbox 意图提交不表示 Broker 发送成功。

现有 `SpringTransactionBoundary` 明确拒绝已有外层事务，只在自己管理的顶层事务提交后返回；
`InboxTemplate.handle` 通过同一边界拥有包含业务变更的完整事务。因此业务日志放在它们
同步返回后，而不是 save、append 或事务 callback 内。外层事务调用会被既有契约拒绝；
回滚或 commit 失败不会到达记录点。不得把该实现移植到允许加入外层事务的边界后继续声称
返回即提交。消息 Handler 用本次调用的局部事实列表承接 Runnable 结果；不跨线程传播。

提交后记录仍处于调用者原有 ExecutionContext 和实际 OTel Scope 内，Formatter 同步投影
可信身份与 C/Trace。没有捕获权限、延长授权 Scope、传播连接或安装新上下文。
默认 Logback 输出失败由其 Appender 处理，不触发业务重放；日志可能丢失，不能当作审计持久性。

这三个业务处理点没有 catch-log-rethrow 或兜底恢复。授权拒绝、找不到订单、冲突和依赖异常
仍按原语义传给真实 HTTP/MQ 最终处理责任方，由相应入口票完成 BIZ/DEP/SYS/ENV 分类和
安全 cause 输出；此处不重复 ERROR，也不新增正常数据库、缓存调用的 INFO。

验证：`BusinessFactLoggingIT` 使用生产 MyBatis Repository、Spring TransactionBoundary、
Jdbc Inbox/Outbox 和真实 MySQL，检查 Boot ECS stdout、提交/回滚/实际连接中断造成的
commit 失败、幂等次数、可信上下文及输出失败。应用单测仅证明责任编排。
