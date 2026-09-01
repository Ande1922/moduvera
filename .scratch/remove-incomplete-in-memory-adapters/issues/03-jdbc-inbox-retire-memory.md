# 03 — 用 JDBC Inbox 验证原子去重并移除内存 Inbox

**What to build:** 让重复投递安全通过生产 JDBC Inbox Repository、真实事务和真实数据库得到证明，同时让纯消息校验与上下文编排继续使用测试内的窄替身，并移除发布模块中的内存 Inbox Repository。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] 同一消费者对重复消息只提交一次业务变更，不同消费者可以独立处理同一消息，且隔离范围包含可信 Tenant Context。
- [x] Inbox 记录与本地业务变更在同一事务中提交；处理失败或事务回滚时，不留下会阻止后续重试的去重记录。
- [x] 缺失可信 Tenant Context 时通过生产 Persistence/Inbox seam fail closed。
- [x] 消息 kind、type、source、destination 校验、失败分类和上下文清理等非持久化行为可以保留为快速测试，但只使用测试源码内的窄 mock、stub 或 lambda。
- [x] 当前内存 Inbox Repository 从生产源码和发布产物中移除，仓库内不再存在对它的引用；PostgreSQL 与聚焦 MySQL 证据继续通过。

## Answer

已集成提交 `4dab6fa`。独立 Standards 与 Spec 审查均通过；PostgreSQL 与 MySQL 的 tenant/consumer 去重、事务回滚与重试证据通过，合并态消息 reactor 的 33 个 Failsafe 集成测试及全部质量门禁成功。
