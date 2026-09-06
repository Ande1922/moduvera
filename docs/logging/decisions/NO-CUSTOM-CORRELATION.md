# 决策：不引入自定义关联字段

曾考虑 `correlation_id` + 各场景执行单元 id（`request_id` / `run_id` / `delivery_id` 等）。否决：业务流用 `trace_id` 已经够；「某服务在这条流里做了什么」用 `trace_id` + `service.name`；调用树交给 OTel，不必在日志里再造一套单元 id。自定义方案还要维护字段名、header 和与 OTel 的互通规则。领域键（`message_id`、`session_id`、`command_id`）不在此列——它们有独立业务含义，保留为场景字段。

活规则见主规范的 [trace 与身份上下文](../LOGGING.md#3-trace-与身份上下文)。
