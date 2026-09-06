# 场景：大模型客户端

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。这是边界交互的特化，不是新的执行单元类型。失败三层见[业务边界失败三层](../LOGGING.md#41-业务边界失败三层)；`retry.*` / `duration_ms` 见[字段契约](../LOGGING.md#2-字段契约)。

平台作为 **LLM 客户端**，logger：`gen_ai.client`。云上默认 INFO（付费、外网、发没发），不要降到与 Redis DEBUG 同一档。

日志抄 OTel GenAI 的**单次调用属性**（用 OTel 已公布的属性名），不把指标仪器整表搬进日志（直方图 / 计数器留在 Metrics）。客户端不要用服务端指标名 `gen_ai.server.time_to_first_token`（那是模型服务打的）。**禁止在 `gen_ai.*` 下自造非 OTel 属性名**：OTel 该属性单位是秒，日志侧统一毫秒，毫秒量用平台字段，与 `duration_ms` 同风格。

| 要回答的问题 | 日志字段与 JSON 类型 | 对齐的 OTel 指标（看板用，不写进每条日志） |
|---|---|---|
| 成没成 | `event.outcome`（string） | — |
| 总耗时 | `duration_ms`（number，`>= 0`） | `gen_ai.client.operation.duration` |
| 首包 / 首 token | `time_to_first_chunk_ms`（number，`>= 0`） | `gen_ai.client.operation.time_to_first_chunk` |
| 用了谁、什么模型 | `gen_ai.provider.name`、`gen_ai.request.model`、`gen_ai.operation.name`（string） | 指标维度 |
| token 用量 | `gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`（integer，`>= 0`） | `gen_ai.client.token.usage` |
| 为何结束 | `gen_ai.response.finish_reasons`（array of string） | — |
| 是否流式 | `gen_ai.request.stream`（boolean） | 首包仅流式有意义 |

`time_to_first_chunk_ms` 语义对齐 OTel 属性 `gen_ai.response.time_to_first_chunk`（OTel 单位为秒，日志侧仍用毫秒），但**不占用** `gen_ai.*` 名。非流式调用省略首包字段，不要写 0。发生过客户端重试则带 `retry.*`。

**不要**进日志：`gen_ai.input.messages` / 输出全文（见[输出、消息与安全](../LOGGING.md#6-输出消息与安全)）、每个 chunk 一条、temperature 等采样参数（需要时 DEBUG）。
