# 05 — 让 Notes 独立消费者验证 Migration 与 Web Starter 契约

**What to build:** 让独立 Notes 示例只通过公开 BOM 与 Starter 获得迁移执行和权限错误默认值，继续证明外部消费者无需依赖内部执行器或复制平台规则。

**Blocked by:** 01 — 新增数据库迁移 VALIDATE 运行模式; 04 — 由 Web Starter 统一映射应用层 Permission Denial.

**Status:** ready-for-agent

- [ ] Notes 通过 BOM 无版本地消费公开 Migration Starter，并发布自己的无副作用 Migration Definition。
- [ ] Notes 选择自身和 Messaging 的 Migration Definitions，不再直接构造或调用数据库迁移执行器。
- [ ] Notes 测试显式初始化 disposable database，并证明迁移、HTTP 与现有消息收发行为仍然工作。
- [ ] Notes 使用 Web Starter 默认 Permission Denial 规则，仅保留 Note Not Found 的 consumer-owned 状态映射。
- [ ] Notes 的权限拒绝返回 HTTP 403、`security.permission-denied` 和 Correlation ID，Not Found 仍返回 HTTP 404。
