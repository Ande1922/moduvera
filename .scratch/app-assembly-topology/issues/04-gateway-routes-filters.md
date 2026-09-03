# 04 — 用服务名 Route/Filter 替换 Gateway 业务代理

**What to build:** 让登录和 Order 公共流通过真正的 Gateway Route/Filter 到提供方 Controller；客户端使用稳定的 `/api/{service}/...` 路径，独立目标接收去除服务前缀的本地版本化路径，同时保留 opaque session exchange、Header/Correlation、安全边界与透明响应语义。

**Blocked by:** 02 — 让 Order HTTP 与库存结果入站行为可复用.

**Status:** resolved

- [x] Identity 登录与 Order 创建/查询使用唯一声明的 `identity`、`order` 服务键；提供方 Controller 只声明本地 `/v1/...` 路径。
- [x] Gateway 对独立服务目标只执行一次服务名前缀去除，并以 Route/Filter 完成 token exchange、可信 Header、Correlation 与外部访问控制。
- [x] 删除手写 `GatewayController` 及业务响应代理；Gateway 不解析 Order Path Variable、不执行业务校验、不构造业务 Problem Details。
- [x] `/internal/**`、Catalog Internal HTTP、Identity exchange/service-token 和管理端点均不能成为公共 Route。
- [x] Gateway integration test 使用真实 Route/Filter 与轻量下游 stub 验证路径、Location、状态、响应体和 Header 透明性。

## Answer

由 `ea72d7d` 实现，并由 `56ed028` 收紧装配边界。Gateway 使用服务名
Route/Filter 完成前缀、token exchange 与 correlation 处理；Gateway
integration tests 验证公共路径、透明响应和内部端点不可达。
