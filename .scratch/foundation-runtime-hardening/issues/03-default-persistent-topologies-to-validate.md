# 03 — 所有持久化参考拓扑默认采用 validate-only

**What to build:** 让正式 App 启动只验证数据库兼容性，而由拥有 disposable database 的测试与参考产品组合显式执行初始化，使两种支持拓扑在最小权限默认值下仍可重复验证。

**Blocked by:** 01 — 新增数据库迁移 VALIDATE 运行模式; 02 — Identity App 改用 Service-owned Migration Definition.

**Status:** resolved

- [x] Catalog、Order、Inventory 与 Monolith 的正常配置选择 `VALIDATE`，不再隐式执行初始化或 DDL。
- [x] 各持久化 App 的集成测试显式选择 `STARTUP + initialize=true` 来管理自己的 disposable database。
- [x] 每个正常 App Assembly 能针对完全迁移的数据库启动，且至少一个聚焦测试证明未初始化或存在 pending migration 时不能就绪。
- [x] Reference-product harness 为所有 disposable database 显式选择启动初始化，并在两种支持拓扑中继续完成新数据库置备。
- [x] 生成 Order 标识符的拓扑继续要求显式 Worker ID，reference harness 明确提供该值且不存在隐式默认值。

## Answer

- 实现提交：`6c5c960`；集成提交：`d845149`。
- Standards 与 Spec 独立复核均无未解决发现；固定 diff 仅修改四个 App 的配置/集成测试和 reference-product harness。
- 五个持久化 App 的 21-module reactor `verify` 通过；Catalog、Order、Inventory、Monolith App IT 共 23 个测试通过。
- `REFERENCE_SKIP_BUILD=1 verification/reference-product/harness/verify.sh` 在 microservices 与 business-core-monolith 两种 fresh-database 拓扑均通过，包括公共 HTTP 契约和 Kafka 恢复阶段。
- `bash -n verification/reference-product/harness/run-topology.sh` 与 `git diff --check` 通过；Ticket 07 Client Group 语义保持不变。
