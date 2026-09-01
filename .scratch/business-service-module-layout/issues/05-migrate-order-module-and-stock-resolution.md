# 05 — 迁移 Order 模块、库存结果入口与 Order App

**What to build:** 将 Order 创建、查询和库存结果消解保持在同一业务模块，通过明确的 `resolvePendingStock` 意图、可复用消息 Inbound Adapter、Remote Catalog 与 Persistence/Publication Outbound Adapters 运行独立 Order App。

**Blocked by:** 03 — 迁移 Catalog 参考业务模块与 Catalog App; 04 — 迁移 Inventory 预留模块与 Inventory App.

**Status:** ready-for-agent

- [ ] Order Module Configuration 只构造协议中立的 Application Service，创建、查询和待库存消解继续共享同一 Order 模型与事务语义。
- [ ] Application 不再暴露意图模糊的一方法 Inventory Result Handler seam；Inbound Message Consumer 调用 `resolvePendingStock`，并保留合法 Mapper 所保护的结果序列化差异。
- [ ] HTTP、消息、Remote Catalog、MyBatis Persistence 与 Outbox Publication 按 Inbound/Outbound 方向归属，Order App 显式选择 Remote Catalog 和所有必需切片。
- [ ] Reserved、Rejected、重复或冲突终态、公共 HTTP、远程 Catalog、Outbox 与跨服务 Repository 集成测试保持通过，并更新所有已迁移实现类型的下游测试引用。

