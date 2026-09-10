# 场景：同步请求-响应（HTTP / gRPC）

本文补充该场景的附加字段与生命周期，只能补充、不能覆盖[主规范](../LOGGING.md)。HTTP 与 gRPC unary（及有界 streaming）同构，共用本模板。gRPC 双向长流、HTTP SSE / WebSocket 走 [SESSION.md](SESSION.md)（gRPC 双向流 logger 为 `rpc.bidi`），**不要**用本条的 `duration_ms` 去表示「连接挂了多久」。

共用的 `duration_ms` / `event.outcome` / 载荷字节见主规范的[字段契约](../LOGGING.md#2-字段契约)。日志自动携带 `trace_id` / `span_id` 与身份字段。

- 执行单元：一次请求；入站 `traceparent` 按主规范的 [trace 规则](../LOGGING.md#3-trace-与身份上下文)合法则继承、非法则重建；HTTP 响应头回写 `X-Trace-Id`
- canonical logger：HTTP 用 `http.request`，gRPC 用 `rpc.server`；**默认 INFO**。结果看 `event.outcome` 与状态字段，5xx / 4xx / gRPC 非 OK **不升级别**
- HTTP 事件字段：`event.outcome`、`http.request.method`、`url.path`、`http.response.status_code`、`duration_ms`、`http.request.body.bytes`、`http.response.body.bytes`、`client.ip`、`user_agent.original`
- **不记 `url.query`**。query 常带凭证，整段脱敏又要在每条请求上解析参数；路由用 `url.path` 即可。禁止改记完整 URL（`url.original` / `url.full`）把 query 夹带进来。以后若要加回，用白名单参数，不要默认打整段
- 载荷字节（见[字段契约](../LOGGING.md#2-字段契约)）：**仅当长度已知**才写。入站请求用 `Content-Length`，不要读请求体流；响应在出口时流往往已写出、不可回读，有 `Content-Length` 才记，否则省略。需要长度时用只计数、不缓存内容的包装，禁止为打日志把整包进堆。流式 / SSE / 大文件下载不包、字段省略。未知则省略，不要写 0
- gRPC 事件字段：`event.outcome`、`rpc.system=grpc`、`rpc.service`、`rpc.method`、`rpc.grpc.status_code`、`duration_ms`；有已知载荷大小时再记字节数
- 业务拒绝与异常最终处理点：全局异常处理器 / gRPC ServerInterceptor。预期业务拒绝按主规范记录业务结果 INFO；实际恢复才记 WARN；无人接盘且需要人工介入才记 ERROR。均不改变 canonical 的 INFO 级别

Servlet 最终 ERROR 的本轮接入边界为现有 Spring MVC `ApiExceptionHandler` Advice：同步和异步 MVC 未知异常在此记录一次安全 cause，并在响应尚未提交时返回安全 500 Problem（`system.unexpected`、同一请求 C）。既有 Coded、校验、Spring 状态映射与 Security 401/403 专用处理器保持其原有响应和记录语义；本轮不提供 domain code 到日志失败类别的映射，也不宣称既有 Coded 依赖错误已经纳入新增最终 ERROR。原生 Servlet/Tomcat 容器日志不纳入最终 ERROR 次数及请求投影验收，不安装 Valve 或 AOP；这些请求仍须通过 canonical、真实响应状态及上下文清理验证，证据分析器单列其原生 ERROR。
