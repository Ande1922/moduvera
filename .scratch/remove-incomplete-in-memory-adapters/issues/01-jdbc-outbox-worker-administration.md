# 01 — 用 JDBC Outbox 验证 Worker 与管理生命周期

**What to build:** 让可靠发布、Outbox Worker 与 Outbox 管理行为通过生产 JDBC Adapter 和真实数据库获得可运行证据，使事务提交、数据库时间、并发 claim、lease、fencing、retry、terminal、redrive 与 cleanup 的测试结果代表受支持的运行时组合。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Durable Publication 通过真实事务验证成功提交、回滚、只读或缺失事务以及 DataSource 绑定约束，发布信号只在业务与 Outbox 事务成功提交后产生。
- [x] Outbox Worker 依赖存储状态的断言通过生产 JDBC Outbox Store 和真实数据库验证，包括 claim、lease、fencing、重试次数、terminal 状态以及未知发送结果。
- [x] redrive、terminal 查询与 bounded cleanup 通过生产 Outbox 管理 seam 验证，并保留消息身份、顺序约束和有效 fencing token。
- [x] PostgreSQL Golden Path 与聚焦的 MySQL 兼容范围继续覆盖其声明的相同语义，容器测试按 `*IT` 进入 Failsafe 生命周期。
- [x] Foundation 只保留可由不可变输入或窄脚本化协作者证明的传输中立决策测试，不反向依赖 Spring/JDBC Starter。

## Answer

已集成提交 `bfc627e`、`912f136`。独立 Standards 与 Spec 审查均通过；`moduvera-message-core` 6 个测试、消息 Starter Surefire 22 个测试及 PostgreSQL/MySQL Failsafe 23 个测试通过，且跨票据相关模块验证通过。
