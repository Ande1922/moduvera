# 场景：定时任务

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。

- 执行单元：一次运行；`trace_id` 由本次运行出生（接入前）
- 开始：logger `job.start`，**默认 INFO**。卡死的任务永远等不到结束事件，开始日志是唯一线索。高频任务不降级：越勤触发越容易叠住。字段：`job_name`（string，稳定任务名）、`trigger`（string：`cron / manual`）。类型用 logger 区分，不带 `phase`，也不用 `event.action`
- 结束 canonical：logger `job.run`，**默认 INFO**。总结果用 `event.outcome`；计数用 `processed` / `failed`（integer，`>= 0`）。其它字段：`job_name`、`trigger`、`duration_ms`
- 查卡死：有 `job.start`、同一 `trace_id` 迟迟没有对应的 `job.run`
- 异常最终处理点：调度包装器。整体失败时 canonical 仍 INFO（`event.outcome=failure`，计数写在 `failed`，不升级别）；无人接盘的 ERROR 在此按[主规范的打印决策](../LOGGING.md#1-打印决策)记录，不另定条数
