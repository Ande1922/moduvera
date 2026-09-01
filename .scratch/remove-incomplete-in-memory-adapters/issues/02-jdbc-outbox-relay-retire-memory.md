# 02 — 让 Outbox Relay 使用运行时存储并移除内存 Outbox

**What to build:** 让 Outbox Relay 的排空、唤醒与停止行为建立在生产 JDBC Outbox 状态机之上，并在所有调用者完成迁移后从发布模块移除不完整的内存 Outbox Store 与 Durable Publication。

**Blocked by:** 01 — 用 JDBC Outbox 验证 Worker 与管理生命周期

**Status:** resolved

- [x] Relay 使用生产 JDBC Outbox Store 验证一次唤醒连续排空多个批次以及并发 wake/poll 信号不会重叠执行 Worker。
- [x] 正常停止允许已开始的发送完成但不再 claim 新工作，强制停止保留未知结果的 in-flight claim 供 lease takeover。
- [x] 发送成功、可重试失败、不可重试失败、中断和未知 ACK 可以由测试源码内的窄脚本化 Message Transport 控制，而不引入正式 Broker fake。
- [x] 当前内存 Outbox Store 与内存 Durable Publication 从生产源码和发布产物中移除，仓库内不再存在对它们的引用。
- [x] 迁移后的容器测试按 `*IT` 运行，相关 Starter 与 Foundation 的聚焦验证保持通过。

## Answer

已集成提交 `2409dc2`。独立 Standards 与 Spec 审查均通过；合并态消息 reactor 验证通过，包含 Message Core 6 个测试、Starter 18 个单测及 Failsafe 33 个集成测试（Relay 5、PostgreSQL 20、MySQL 8），Spotless、PMD 与 JaCoCo 均成功。
