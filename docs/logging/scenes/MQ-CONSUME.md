# 场景：MQ / 事件消费

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。`disposition` / `retry.*` / `duration_ms` / 载荷大小见[字段契约](../LOGGING.md#2-字段契约)；耗尽不打恢复 WARN 见[重试规则](../LOGGING.md#51-重试)。

- 执行单元：一次消息投递处理；`trace_id` 从消息属性读取（接入前）；再次处理用 `message_id` + `retry.attempt` 区分，不靠 `span_id`
- `trace_id` 缺失时按消息来源区分：**内部服务间消息**约定必须携带；缺失时以“已执行上下文兜底”的恢复角色生成新上下文并记一条 WARN，提示修复上游。**外部 / 设备来源消息**（如 MQTT 上报）本身就是业务流起点，正常在此生成，不告警、不要求设备端提供（固件不可控）
- canonical logger：`mq.consume`；**默认 INFO**。`event.outcome` 只取 `success` / `failure`；还将投递 → `disposition=retry`，进死信 → `disposition=dead_letter`（成功省略 `disposition`）
- 事件字段：`event.outcome`、`disposition`（若有）、`messaging.system`（string，稳定中间件名）、`topic`（string）、`message_id`（string）、`messaging.message.body.size`、`duration_ms`；发生过再次处理则带 `retry.attempt`，已配置上限时再带 `retry.max_attempts`（从客户端框架读取，见[字段契约](../LOGGING.md#2-字段契约)与 [Java 绑定](../bindings/JAVA-SPRING-BOOT.md)）
- 异常最终处理点：消费包装器（决定重试 / 死信的地方）。进死信时 canonical 仍 INFO（`event.outcome=failure`、`disposition=dead_letter`，不升级别）；DLQ 只是处置结果，不自动决定 ERROR。只有无人接盘且需要人工介入时，才在此按[主规范的打印决策](../LOGGING.md#1-打印决策)另记 ERROR
