# 场景：设备会话与交互

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。设备链路无法透传 `traceparent` 的原因见 [TRACE-BREAK.md](../decisions/TRACE-BREAK.md)。

- 执行单元有两层：**连接会话**（领域键 `session_id`，生命周期内所有日志携带）与会话内的**每次下行指令或上行消息处理**
- 连接建立是生命周期 INFO：logger `device.session`，带 `phase=start`、`device_id`、`session_id`。连接断开是该会话的 canonical INFO：同一 logger，带 `phase=end`、`event.outcome`、`device_id`、`session_id`、`duration_ms` 与 `disconnect_reason`（string，场景内稳定枚举；来源无法细分时为 `unknown`）。`phase` 为 string：`start / end`
- 下行指令从接受发送开始，到设备回执或超时结束；上行消息从收到开始，到业务处理完成结束。每个单元只记一条 `device.command` canonical INFO，带 `event.outcome`、`device_id`、`session_id`、`direction`（string：`downlink / uplink`）、`command_type`（string，稳定枚举）及 `duration_ms`。下行必带 `command_id`（string），上行必带来源 `message_id`（string；协议不提供时由入口 Adapter 生成）
- 设备链路无法透传 `traceparent`：trace 在平台侧入口出生；下行指令与回执通过 `command_id` 配对，上行处理通过 `message_id` 关联
- 设备若已走 MQTT / 设备 WebSocket，用本条，**不要**再套 [SESSION.md](SESSION.md)
- 异常最终处理点：设备网关的收发处理器
