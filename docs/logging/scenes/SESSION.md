# 场景：长连接入站（WebSocket / SSE / gRPC 双向流）

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。应用侧长连接与 [DEVICE.md](DEVICE.md) 同构，**不是** [INBOUND-REQUEST.md](INBOUND-REQUEST.md) 的请求-响应。若用 `http.request` / `rpc.server` 一条 canonical 撑满整个连接或双向流，`duration_ms` 会变成「挂了多久」，超时排查会误导。

- 执行单元有两层：**连接 / 流会话**（领域键 `session_id`，生命周期内所有日志携带）与会话内**需要独立处理的业务消息**；ping / pong 不是执行单元
- 会话 logger：WebSocket `session.ws`、SSE `session.sse`、gRPC 双向流 `rpc.bidi`。建立时记录生命周期 INFO，带 `phase=start`、`session_id`；断开时记录 canonical INFO，带 `phase=end`、`event.outcome`、`session_id`、`duration_ms` 与 `disconnect_reason`（string，场景内稳定枚举；来源无法细分时为 `unknown`）。`phase` 为 string：`start / end`；`duration_ms` 表示会话寿命
- 业务消息 canonical：WebSocket / SSE 用 `session.message`，gRPC 双向流用 `rpc.message`。消息携带合法 trace 上下文时继承，否则为该消息生成新的 `trace_id` / `span_id`；身份字段从会话继承，并用 `session_id` 关联回会话。处理完成时记录一条 INFO，带 `event.outcome`、`session_id`、`message_type`（string，场景内稳定枚举）、可用时的 `message_id`（string）及 `duration_ms`
- 业务消息处理过程中若另有业务事实成立，按 `event.action` 再记业务事实 INFO；它不能替代消息 canonical。过程帧与 ping / pong 只用 DEBUG 或不记
- SSE 若实现为一条不结束的 HTTP 请求：[INBOUND-REQUEST.md](INBOUND-REQUEST.md) 的 Filter 只打握手，结束时另打 `session.sse` canonical；禁止把整段流式寿命写进入站 `http.request` 的 `duration_ms`
- 设备长连接走 [DEVICE.md](DEVICE.md)，不走本条
- 异常最终处理点：会话使用连接 / 流生命周期处理器（WebSocket handler、SSE completion callback、gRPC `ServerCall.Listener` 的 `onComplete` / `onCancel`）；业务消息使用消息处理包装器
