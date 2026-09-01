# 07 — 固化运行时 Adapter 证据规则并完成全链路验证

**What to build:** 将生产运行时 Adapter、测试内窄替身和真实基础设施证据的边界固化为仓库贡献规则，并以完整构建和两种受支持应用拓扑证明移除内存替身没有改变产品契约或运行时装配。

**Blocked by:** 02 — 让 Outbox Relay 使用运行时存储并移除内存 Outbox；03 — 用 JDBC Inbox 验证原子去重并移除内存 Inbox；04 — 让 Catalog 测试分层并移除内存 Product Repository；05 — 让 Order 测试分层并移除内存 Order 协作者；06 — 让 Inventory 测试分层并移除内存 Store

**Status:** resolved

- [x] 仓库测试指导明确：基础设施语义由生产 Adapter 与真实组件证明，Application 编排可使用测试源码内的窄 mock、scripted stub、lambda 或 recording spy。
- [x] 指导说明未来内存运行时 Adapter 需要真实受支持消费者并通过同等适用契约证据，不以类名规则一概禁止所有未来 `InMemory` 实现。
- [x] 七个已识别的不完整内存实现及其引用均已从生产源码、测试源码和发布产物表面消失，且未迁移到共享 test support 或新的 fake artifact。
- [x] App Assembly Bean 选择、公共 HTTP 与消息契约、Destination、数据库 schema、事务语义和受支持基础设施矩阵保持不变。
- [x] 完整 Maven Reactor 验证通过，五服务 Golden Path 与业务核心模块化单体使用同一公共黑盒验收步骤通过，并保留足够的租户、业务身份和消息生命周期失败诊断。

## Answer

已集成提交 `b0ef30b`、`2a74ac7`。Standards 首审指出 `AGENTS.md` 重复 ADR 0034，改为完整触发条件的单一 ADR 指针后，Standards 与 Spec 复审均通过。完整 Maven Reactor 30/30 模块验证成功；公共验收脚本在隔离宿主机端口后对 `microservices` 与 `business-core-monolith` 两种拓扑均通过，并覆盖 Kafka 停机提交与恢复重放。七个旧实现名在源码/测试、437 个编译文件和 27 个发布归档中的扫描结果均为零。
