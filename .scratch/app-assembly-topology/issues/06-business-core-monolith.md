# 06 — 运行 Catalog、Order、Inventory 业务核心模块化单体

**What to build:** 把 `app-monolith` 变成 Catalog、Order、Inventory 的真实 composition root：选择 Local `CatalogApi`、服务拥有的 HTTP/消息 Inbound、组件持久化与 Migration，并继续通过同一 Kafka、Outbox/Inbox 完成完整订单履约。

**Blocked by:** 01 — 显式装配 Catalog Local API、Persistence 与 Internal HTTP; 02 — 让 Order HTTP 与库存结果入站行为可复用; 03 — 让 Inventory 预留命令处理可复用; 04 — 用服务名 Route/Filter 替换 Gateway 业务代理.

**Status:** resolved

- [x] Monolith Application Context 对每项必需 Service API 恰好存在一个实现，Order 使用 Local `CatalogApi`，Remote Catalog Adapter 不存在；缺失或重复实现时启动失败。
- [x] Order 与 Inventory 在同一进程仍通过相同 Kafka destination、序列化契约、Outbox relay、Reliable Consumer 和 Inbox 交互，不引入 LocalTransport。
- [x] Monolith 为 Order 公共 Controller 恰好增加一次 `/api/order` 前缀；Catalog Internal Controller、Actuator 与其他内部端点不被公共前缀改写。
- [x] Gateway 的组合目标模式保留完整公共路径，而 Identity 仍按独立服务规则去前缀。
- [x] Monolith integration tests 覆盖 Local Catalog 选择、确认/拒绝、重复交付、bounded Eventually 与 Kafka 故障恢复。

## Answer

由 `ea72d7d` 实现。`app-monolith` 显式组合 Local Catalog、Order/Inventory
Application、Persistence 与服务拥有的 Inbound Adapter，并由真实
PostgreSQL/Kafka integration tests 验证确认、拒绝、重复交付和恢复。
