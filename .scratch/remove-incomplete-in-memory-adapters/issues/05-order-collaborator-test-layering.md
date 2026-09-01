# 05 — 让 Order 测试分层并移除内存 Order 协作者

**What to build:** 让下单编排通过测试内 recording 协作者快速证明，并让订单插入、重建、状态更新、事务回滚和乐观版本行为由生产 MyBatis Repository 与真实数据库证明，随后移除两个发布型内存协作者。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 下单用例通过测试源码内的 recording Repository 和 recording Publisher 验证一次显式 Transaction Boundary、一个 PENDING_STOCK 订单以及一次库存预占命令发布。
- [ ] recording 协作者只记录当前编排所需交互，不模拟持久化版本、数据库事务、Broker ACK 或完整 Repository 行为。
- [ ] 订单插入、重新加载、状态变更、审计数据、乐观版本冲突、Tenant Context 隔离与事务回滚通过生产 MyBatis Repository 和真实数据库验证。
- [ ] 当前内存 Order Repository 与内存 Reserve Inventory Publisher 从生产源码和发布产物中移除，仓库内不再存在对它们的引用。
- [ ] Order 聚焦测试、业务 Repository 兼容性测试及 Order App integration test 保持通过，公开 API 与消息契约不变。
