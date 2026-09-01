# 07 — 固化运行时 Adapter 证据规则并完成全链路验证

**What to build:** 将生产运行时 Adapter、测试内窄替身和真实基础设施证据的边界固化为仓库贡献规则，并以完整构建和两种受支持应用拓扑证明移除内存替身没有改变产品契约或运行时装配。

**Blocked by:** 02 — 让 Outbox Relay 使用运行时存储并移除内存 Outbox；03 — 用 JDBC Inbox 验证原子去重并移除内存 Inbox；04 — 让 Catalog 测试分层并移除内存 Product Repository；05 — 让 Order 测试分层并移除内存 Order 协作者；06 — 让 Inventory 测试分层并移除内存 Store

**Status:** ready-for-agent

- [ ] 仓库测试指导明确：基础设施语义由生产 Adapter 与真实组件证明，Application 编排可使用测试源码内的窄 mock、scripted stub、lambda 或 recording spy。
- [ ] 指导说明未来内存运行时 Adapter 需要真实受支持消费者并通过同等适用契约证据，不以类名规则一概禁止所有未来 `InMemory` 实现。
- [ ] 七个已识别的不完整内存实现及其引用均已从生产源码、测试源码和发布产物表面消失，且未迁移到共享 test support 或新的 fake artifact。
- [ ] App Assembly Bean 选择、公共 HTTP 与消息契约、Destination、数据库 schema、事务语义和受支持基础设施矩阵保持不变。
- [ ] 完整 Maven Reactor 验证通过，五服务 Golden Path 与业务核心模块化单体使用同一公共黑盒验收步骤通过，并保留足够的租户、业务身份和消息生命周期失败诊断。
