# 场景：MQ 生产

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。不是独立执行单元。失败三层见[业务边界失败三层](../LOGGING.md#41-业务边界失败三层)；重试状态表见[重试规则](../LOGGING.md#51-重试)。消费端 canonical 见 [MQ-CONSUME.md](MQ-CONSUME.md)。

- logger：`mq.produce`
- 发往设备、合作方、另一业务域：**通常是业务边界**，成功也 INFO——"发没发、回没回"的唯一证据
- 同一业务域内部投递、且消费端已有 canonical：**通常不构成业务边界**；成功默认 DEBUG 或不记。失败不能只记 DEBUG：恢复 / 最终记录能够指认该次发送时不补结果，否则由 `mq.produce` 记录 INFO + `event.outcome=failure`
- 事件字段：`event.outcome`、`messaging.system`（string，稳定中间件名）、`topic`（string）、`message_id`（string）、`messaging.message.body.size`、`duration_ms`；有异常对象的失败可以加 `error.type`，已有稳定语义分类时可以加 `error.code`，最终 ERROR 必须加 `error.code`。发生过客户端重试则带 `retry.attempt`；已配置上限时再带 `retry.max_attempts`

**需要记录调用结果时，时机是 broker 确认（或失败），不是入队成功。** 一次可观察尝试是一次应用发送调用及其对应的 broker ack / 失败：同步在该次 API 返回或抛错时记录；异步在该次完成回调时记录。应用级重试每次调用各有一条；客户端内部重发只有暴露独立回调时才逐次记录，否则在最终回调用 `retry.*` 表达。入队成功但尚未 ack 不是“已发送”。

同步失败且异常继续往上抛：业务边界发送必须、非边界发送按上述条件，由拦截器记录调用结果（`event.outcome=failure`、`duration_ms`、身份字段）；该结果保持 INFO，不代打 WARN / ERROR，异常交给本单元最终处理点。

异步 callback 运行在生产者网络线程，发起这次发送的执行单元往往已经结束、上下文已清。**callback 就是这次发送的最终处理点**：成功仍按前述分类——业务边界记录交互结果 INFO，非边界使用 DEBUG 或不记；失败无人接盘时把调用结果与最终错误合成一条 ERROR，带 `event.outcome`、`duration_ms`、`retry.*`（若有）、`error.code` 和 cause（若有），禁止再抛给已经返回的请求。发送前快照完整当前 trace 上下文（至少 `trace_id`、`span_id`）与身份字段，callback 里恢复后再打；它是原发送的延续，不创建新的执行单元。

**失败正文不进运行日志。** `mq.produce` 只带 `message_id`、`topic`、分区键 / routing key（若有）、`messaging.message.body.size`、业务主键；成功失败都不写全文（见[输出、消息与安全](../LOGGING.md#6-输出消息与安全)）。消息是否可恢复、如何 outbox / 落盘，不由本规范规定；可靠性兜底可以使用本地持久化，但不能把运行日志当作可重放消息存储。
