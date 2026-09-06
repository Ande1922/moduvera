# 场景：出站 HTTP / RPC / 对象存储

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。不是独立执行单元，归属于发起它的单元。失败三层与出站 `traceparent` 见[业务边界失败三层](../LOGGING.md#41-业务边界失败三层)；重试状态表见[重试规则](../LOGGING.md#51-重试)。自有库表 / 缓存不是本条。

- 先按主规范的[执行单元与调用分类](../LOGGING.md#4-执行单元与调用分类)判断是否为业务边界；本场景后续规则只覆盖被判定为业务边界的出站 HTTP / RPC / 跨账号对象存储。logger 为 `http.client` 或 `rpc.client`，成功失败都是 INFO，不定 WARN / ERROR。不能只因“内部服务”或“高频”降 DEBUG；不构成业务边界的调用及自有库表 / 缓存按主规范处理
- HTTP 事件字段：`event.outcome`、`target`（string，稳定目标系统名）、`http.request.method`、`url.path`、`http.response.status_code`、`duration_ms`、`http.request.body.bytes`、`http.response.body.bytes`；发生过客户端重试则带 `retry.attempt`，已配置上限时再带 `retry.max_attempts`（最终成功那条也带）。`url.path` 不含 query，禁止把带 query 的完整 URL 写入任何字段（与 [INBOUND-REQUEST.md](INBOUND-REQUEST.md) 一致）
- 载荷字节：用客户端知道的 Content-Length / 已统计的写出字节；未知则省略，不要写 0（见[字段契约](../LOGGING.md#2-字段契约)）
- gRPC 出站字段：`event.outcome`、`target`、`rpc.system=grpc`、`rpc.service`、`rpc.method`、`rpc.grpc.status_code`、`duration_ms`，重试字段同上
- 跨账号对象存储 SDK / S3 API 按本条（一次调用一条 INFO，带 `event.outcome`、`bucket_name`（string）、`object_key`（string，须符合安全白名单）、已知时的 `object_size_bytes`（integer，`>= 0`）及 `duration_ms`）

拦截器必须包住**每一次可观察的实际尝试**（含客户端暴露的内部重试），每次结束都记录交互结果 INFO。中间失败且框架**确定还会再试**时，重试框架监听器另外记录 WARN + `retry.*`；耗尽当次仍有交互结果 INFO，但不再记录恢复 WARN。最终成功的交互结果也必须保留 `retry.*`。若库内重发不暴露独立尝试，禁止编造逐次日志，由监听器在最终结果补足统一的 `retry.*`。
