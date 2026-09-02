# 06 — Identity Token 调用迁移到 declarative Client Group

**What to build:** 让 Order 在自己的 Outbound HTTP Adapter 内通过声明式 Identity transport 和具名 Client Group 获取委托上下文 Service Token，同时保留已有安全作用域缓存与失败语义。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] 声明式 Identity transport 保持在 Order 的 transport Adapter 边界内，不污染 provider API 或 Application/Domain 代码。
- [x] `identity` Client Group 拥有必填 base URL、独立连接/读取超时、Apache HC5 连接池和原生 HTTP observations；缺失或不可用配置在启动时失败。
- [x] 请求继续携带 Basic 客户端认证以及 Tenant、Audience 和原始 Initiator，成功和空响应、非成功状态、transport failure 均转换为现有 Order-side 语义。
- [x] Service Token cache 继续按 service、audience、Tenant 和 Initiator 隔离，并保留过期偏移、容量上限、同 key single-flight 与刷新失败时 fail-closed 行为。
- [x] 不启用自动重试、CircuitBreaker、额外并发限制，也不创建通用 HTTP Client Starter 或 Cache API。

## Answer

Implemented by commits `6aeff00` and `7171518`, independently reviewed on standards and specification axes, and integrated into `codex/foundation-hardening-integration`. Real HC5 transport tests prove a 503 token POST is attempted once and the configured read timeout is honored; Order Service and Order App scoped verification passed, while Monolith remains free of HC5.
