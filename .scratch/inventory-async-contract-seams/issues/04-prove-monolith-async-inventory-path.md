# 04 — 验证模块化单体复用同一异步库存路径

**What to build:** 让 Business-core Modular Monolith 继续通过同一个 Reserve Inventory 消息 Inbound Adapter 完成 Order 到 Inventory 再回到 Order 的异步链路，同时不暴露同步 Inventory Java API。

**Blocked by:** 02 — 让库存预占消息直接调用 Application Service

**Status:** ready-for-agent

- [ ] 模块化单体装配 Inventory Application Service 与消息 Inbound Adapter，但不存在公开 Inventory Java API Bean 或进程内同步捷径。
- [ ] 订单经 Outbox、真实 Kafka、Inventory Inbox 与结果消息完成确认或拒绝，重复投递不重复扣减库存或发布结果。
- [ ] Catalog 与 Order 的既有直接 Local Call 装配保持不变，Inventory 的异步专用语义不因单进程拓扑而改变。
- [ ] Modular Monolith 的聚焦集成验证通过。
