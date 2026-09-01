# 05 — 固化异步专用 Inventory 接缝的架构守卫

**What to build:** 让仓库结构测试明确阻止异步专用库存预占重新获得同步 Java Service API，并继续保护协议无关契约、提供方 Inbound Adapter 所有权和 App Assembly 装配职责。

**Blocked by:** 02 — 让库存预占消息直接调用 Application Service

**Status:** ready-for-agent

- [ ] 架构规则会在异步专用 Reserve Inventory Handler 重新依赖公开同步接口时失败，并对当前直接 Application Service 路径通过。
- [ ] Inventory API 模块继续不依赖 Spring Web、Spring Messaging、Kafka、消息 starter 或序列化框架。
- [ ] App Assemblies 继续只选择和激活业务 Inbound Adapter，不拥有 Message Handler、Mapper 或业务回调。
- [ ] Architecture testkit 的聚焦测试及其格式化、静态检查通过。
