# 12 — 证明并发不超卖与跨线程租户隔离

**What to build:** 在单线程基线、复用平台线程池和虚拟线程三种执行模型下，证明 Tenant Context 能被显式捕获、传播、恢复和清理；并发订单既不会跨租户串扰，也不会超卖或产生部分库存预留。

**Blocked by:** 10 — 通过 Gateway 暴露完整公共订单流.

**Status:** resolved

- [x] 单线程 HTTP、Repository 和消息处理建立 tenant-a/tenant-b 隔离基线，跨租户查询统一不泄漏存在性。
- [x] 同一组隔离场景在 JDK 26 虚拟线程执行模型下通过，包括 HTTP 请求、消息处理及平台管理的异步任务。
- [x] 同一组隔离场景在可复用平台线程池下通过；使用单工作线程交替执行 tenant-a、tenant-b 和无上下文任务，证明线程复用不残留前一租户。
- [x] 平台提供明确的 context capture/restore seam；跨线程任务未使用该 seam 时必须 fail closed，不能使用默认租户、继承陈旧上下文或静默退化。
- [x] 正常完成、异常、取消、超时和任务拒绝路径均恢复或清理调用线程与工作线程上下文，后续任务看不到前一任务的 actor、tenant、permissions 或 correlation。
- [x] tenant-a 与 tenant-b 并发处理时，数据库行、权限判断、Outbox/Inbox Envelope 和结构化日志关联字段均不串租户。
- [x] 多个公共订单并发竞争有限库存时，确认总量不超过库存、库存不为负，失败订单为 `REJECTED`，且多行预留全成或全回滚。
- [x] 架构测试禁止 Domain/Application 直接操作 ThreadLocal、ScopedValue、Executor context wrapper 或 `ExecutionContextHolder`；传播机制留在系统入口和平台基础设施 seam。

