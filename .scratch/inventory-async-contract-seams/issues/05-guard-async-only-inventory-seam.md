# 05 — 固化异步专用 Inventory 接缝的架构守卫

**What to build:** 让仓库结构测试明确阻止异步专用库存预占重新获得同步 Java Service API，并继续保护协议无关契约、提供方 Inbound Adapter 所有权和 App Assembly 装配职责。

**Blocked by:** 02 — 让库存预占消息直接调用 Application Service

**Status:** resolved

- [x] 架构规则会在异步专用 Reserve Inventory Handler 重新依赖公开同步接口时失败，并对当前直接 Application Service 路径通过。
- [x] Inventory API 模块继续不依赖 Spring Web、Spring Messaging、Kafka、消息 starter 或序列化框架。
- [x] App Assemblies 继续只选择和激活业务 Inbound Adapter，不拥有 Message Handler、Mapper 或业务回调。
- [x] Architecture testkit 的聚焦测试及其格式化、静态检查通过。

**Answer:** 已由 `9c39a2e`、`b6bbe10` 与 `6000416` 完成。结构化规则覆盖处理器/接口改名与任意返回类型，禁止 Service API 依赖 message-core 和 Kafka starter，同时保留合法直接查询 API；24 项架构测试、两条 24 模块 `-am` 验证及最终 Standards/Spec 审查均通过。
