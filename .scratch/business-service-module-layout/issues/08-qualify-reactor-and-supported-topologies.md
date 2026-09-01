# 08 — 验证 Reactor 与两种受支持应用拓扑

**What to build:** 对集成后的完整变更运行仓库级质量门和同一套公共黑盒验收，证明五 App 微服务 Golden Path 与业务核心模块化单体在 package、装配和迁移重构后仍具有相同客户端可见行为。

**Blocked by:** 07 — 收缩旧形态并锁定模块与 Adapter 架构.

**Status:** ready-for-agent

- [ ] 完整 Reactor 的编译、单元、集成、架构、格式化、PMD 和 JaCoCo 检查通过，并保留当前执行证据。
- [ ] 微服务 Golden Path 与业务核心模块化单体均通过未放宽的公共 HTTP 黑盒 harness。
- [ ] 验收覆盖订单创建/查询、库存确认/拒绝、Tenant 隔离、可信上下文、Outbox/Inbox、最终一致性与公共契约兼容。
- [ ] 任何环境失败、未执行检查或支持矩阵限制被明确记录；仅在全部必需证据通过后将本 effort 标记完成。

