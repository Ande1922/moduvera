# 06 — 让 Inventory 测试分层并移除内存 Store

**What to build:** 让 Inventory Application 的授权与发布决策通过测试内脚本化 Store 结果快速验证，并让库存预占的持久化、幂等和并发语义由生产 MyBatis Store 与真实数据库证明，随后移除第二套内存库存算法。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Inventory Application 使用测试源码内的脚本化 Store 决策验证授权、重复命令结果映射以及只在创建新结果时发布集成事实。
- [ ] 测试替身不重新实现库存扣减、行锁、幂等、Tenant Context 隔离或并发算法。
- [ ] 足量库存的原子预占、任一条目不足时全拒、重复命令幂等、存储结果重建、并发竞争和租户隔离通过生产 MyBatis Store 与真实数据库验证。
- [ ] 当前内存 Inventory Store 从生产源码和发布产物中移除，仓库内不再存在对它的引用。
- [ ] Inventory 聚焦测试、业务 Repository 兼容性测试及 Inventory App integration test 保持通过，且不改变另一个规格负责的异步 Service API seam。
