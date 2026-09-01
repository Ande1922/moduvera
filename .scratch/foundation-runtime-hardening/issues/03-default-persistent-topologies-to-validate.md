# 03 — 所有持久化参考拓扑默认采用 validate-only

**What to build:** 让正式 App 启动只验证数据库兼容性，而由拥有 disposable database 的测试与参考产品组合显式执行初始化，使两种支持拓扑在最小权限默认值下仍可重复验证。

**Blocked by:** 01 — 新增数据库迁移 VALIDATE 运行模式; 02 — Identity App 改用 Service-owned Migration Definition.

**Status:** ready-for-agent

- [ ] Catalog、Order、Inventory 与 Monolith 的正常配置选择 `VALIDATE`，不再隐式执行初始化或 DDL。
- [ ] 各持久化 App 的集成测试显式选择 `STARTUP + initialize=true` 来管理自己的 disposable database。
- [ ] 每个正常 App Assembly 能针对完全迁移的数据库启动，且至少一个聚焦测试证明未初始化或存在 pending migration 时不能就绪。
- [ ] Reference-product harness 为所有 disposable database 显式选择启动初始化，并在两种支持拓扑中继续完成新数据库置备。
- [ ] 生成 Order 标识符的拓扑继续要求显式 Worker ID，reference harness 明确提供该值且不存在隐式默认值。
