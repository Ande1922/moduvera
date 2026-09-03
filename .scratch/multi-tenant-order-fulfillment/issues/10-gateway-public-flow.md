# 10 — 通过 Gateway 暴露完整公共订单流

**What to build:** 让租户用户只面对 Gateway：通过公共登录获得 opaque token，随后创建订单并查询最终履约状态；Gateway 在内部完成 token exchange，Catalog、Identity exchange 和消息管理路由保持不可公开。

**Blocked by:** 08 — 闭合 Order 与 Inventory 异步履约; 09 — 交付 Identity Session 与 Internal JWT.

**Status:** resolved

- [x] `gateway-app` 暴露正常登录和 Order 公共版本化 API，并将 opaque token 交换成 internal JWT 后转发。
- [x] Gateway 不把 internal JWT 返回给浏览器，不记录 opaque/internal token，也不将客户端提交的 Tenant ID 作为可信输入。
- [x] Catalog 内部 HTTP、Identity token exchange 和消息管理端点不能从公共 Gateway 路由访问。
- [x] 公共 happy path 从登录、创建订单到查询 `CONFIRMED`/`REJECTED` 全部通过 Gateway 完成。
- [x] 无 token、过期/无效 session、无创建权限、validation、未找到与跨租户读取分别表现为稳定的 401、403、400 或不泄漏存在性的 404 Problem Details。
- [x] 成功响应保持未包装，Order API major version 保留在 Service-owned path，Gateway 不重写业务版本。
- [x] correlation ID 在 Gateway 生成或透传，并能关联 Catalog HTTP、Order、Kafka 和 Inventory 日志。

## Answer

由 `71d3f37` 实现，并由 `56ed028` 收紧 Gateway 装配边界。Gateway 的公共
登录与 Order 流、内部 token exchange、路由隔离及 RFC 9457 错误契约均有
integration test 与公共黑盒验证均通过。
