# 04 — 让 Catalog 测试分层并移除内存 Product Repository

**What to build:** 让 Catalog Application 的授权与结果映射保持快速可验证，同时让商品保存、重建和租户隔离声明由生产 MyBatis Repository 与真实数据库支撑，并从发布模块移除不完整的内存 Product Repository。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Catalog Application 的授权、not-found 和 Product Snapshot 映射使用测试源码内的局部 stub 验证，不把该替身作为持久化或租户隔离证据。
- [x] 商品保存、重新加载、审计数据和当前 Tenant Context 隔离通过生产 MyBatis Repository 与真实数据库观察。
- [x] 缺失 Tenant Context 时的 fail-closed 行为以及跨租户不可见性通过最高有效生产 seam 验证。
- [x] 当前内存 Product Repository 从生产源码和发布产物中移除，仓库内不再存在对它的引用。
- [x] Catalog 聚焦测试、业务 Repository 兼容性测试及相关 App integration test 保持通过。

## Answer

已集成提交 `db67f7d`、`e622e76`。独立 Standards 与 Spec 审查均通过；Catalog 聚焦测试、PostgreSQL Application IT、MySQL Repository 兼容性验证及跨票据相关模块验证通过。
