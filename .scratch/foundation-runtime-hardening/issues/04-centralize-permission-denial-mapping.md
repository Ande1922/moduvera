# 04 — 由 Web Starter 统一映射应用层 Permission Denial

**What to build:** 让所有 Web Starter 消费者自动把应用用例的权限拒绝呈现为稳定的 HTTP 403 Problem Details，同时保留服务自有错误语义和安全过滤器错误的区别。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Web Starter 默认把 `PermissionDeniedException` 映射为 HTTP 403，并保留 `security.permission-denied` 错误码、RFC 9457 结构和 Correlation ID。
- [ ] 默认权限规则采用最低优先级，provider-owned Not Found、Conflict 等状态贡献者仍可优先决定自己的异常语义。
- [ ] Order 与 Catalog 删除重复的权限映射，但现有 Not Found 行为不变。
- [ ] Resource Server 的未认证与过滤器访问拒绝继续分别使用 `security.unauthenticated` 和 `security.forbidden`。
- [ ] Starter 合约测试通过公开 HTTP/异常处理结果验证行为，不依赖私有解析器实现细节。
