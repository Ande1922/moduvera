# 06 — 用新配置切片重组业务核心模块化单体

**What to build:** 让业务核心模块化单体显式组合 Catalog、Order、Inventory 的 Module、Inbound/Outbound Adapters 与 Migration Definitions，选择 Local Catalog 并继续通过真实 Kafka 完成 Order 到 Inventory 再回到 Order 的业务链路。

**Blocked by:** 03 — 迁移 Catalog 参考业务模块与 Catalog App; 04 — 迁移 Inventory 预留模块与 Inventory App; 05 — 迁移 Order 模块、库存结果入口与 Order App.

**Status:** claimed

- [ ] 每项必需业务 seam 恰好选择一个实现，Order 使用 Local Catalog，Remote Catalog Adapter 不存在。
- [ ] Monolith 显式选择三个服务 Migration Definitions 和一次消息平台 Definition，并通过通用 startup 策略执行，不直接构造 Database Migrator 或复制迁移元数据。
- [ ] Order 公共路径前缀、Catalog Internal HTTP、Tenant/Actor/Initiator/Correlation、Outbox/Inbox 和真实 Kafka 语义保持不变。
- [ ] Monolith 集成测试覆盖 Local/Remote 选择、确认、拒绝、重复交付、迁移历史隔离以及未选择 Adapter 缺席。
