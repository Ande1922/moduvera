# 07 — 验收双拓扑并提升产品支持状态

**What to build:** 让同一套公共 HTTP 黑盒契约分别运行于五 App 微服务 Golden Path 和业务核心模块化单体，通过相同外部 URL 与断言证明客户端兼容、安全边界和 Kafka 最终业务结果；仅在全部证据通过后提升支持状态。

**Blocked by:** 05 — 锁定 App Assembly 与 Inbound Adapter 所有权; 06 — 运行 Catalog、Order、Inventory 业务核心模块化单体.

**Status:** resolved

- [x] 共享 harness 只通过拓扑配置切换目标 App 集合、Gateway prefix policy 与地址，不复制客户端步骤或放宽断言。
- [x] 两次运行覆盖登录、创建/查询订单、认证、权限、校验、未找到、跨租户、RFC 9457、Location、Correlation 和未包装成功响应。
- [x] 两次运行通过公共结果观察 Kafka 驱动的确认/拒绝终态，并具备重复交付与恢复语义的对应执行证据。
- [x] `/internal/**`、Catalog Internal HTTP、Actuator 和 Identity 内部端点不被公共 Gateway 暴露或公共前缀误改写。
- [x] Reactor、相关 App integration tests、架构测试与两种拓扑黑盒契约全部通过后，README、Context、模块地图和产品状态同步为真实支持范围。
