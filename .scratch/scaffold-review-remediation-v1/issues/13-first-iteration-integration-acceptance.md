Type: issue
Status: ready-for-agent
Blocked by: 05, 06, 07, 09, 10, 11, 12

# 13 — 完成第一迭代集成验收

## Outcome

在所有前置整改按依赖顺序集成后，用仓库拥有的门禁、场景验证和 tracker 证据证明第一迭代形成可复现闭环，且没有把明确延期的主题伪装为本轮完成。

## Acceptance Criteria

- [ ] 前置 ticket 的实现提交、双轴 Review、修复提交和窄测证据均可追溯，集成顺序满足 blocker 图且不存在未解决冲突或验收 finding。
- [ ] 最终集成状态通过仓库 Normal gate 和干净 Maven Reactor 验证；门禁后的代码、配置、测试或脚本没有再次变化。
- [ ] 适用 Scenario gate 覆盖五 App Golden Path、business-core monolith、两个 run slot 的隔离与 Kafka health，以及共享镜像的构建和代表性运行 smoke。
- [ ] Tenant ID 的签发、消费、迁移和真实数据库证据，Inventory 的 Domain/Adapter/Application 分层证据，以及 Catalog 受支持装配入口均在集成状态下通过。
- [ ] 项目 Skills、交付 forward test、新 Business Service forward test、tracker checker、changed-code 报告与 pre-push fixture 在集成状态下通过。
- [ ] 受影响的产品边界、架构文档、系统评审 finding 修复提交和 tracker Answer 与实际结果一致；父规格保持本轮约定的状态，除非获得单独关闭授权。
- [ ] ExecutionContext 跨执行模型、受治理可观测性、Reliable Inbound 角色、托管 CI、Mutation/数值阈值和 Outbox 性能基线仍明确留在后续工作，没有被本轮局部实现宣称解决。

## Verification

- 从最终集成提交运行 Normal gate、完整 Reactor 和所有适用 Scenario/image smoke。
- 运行 tracker、Skill、门禁、迁移、架构和契约检查，并审计证据目录与提交引用。
- 对照父规格逐项确认第一迭代范围和显式延期项。

## Out of Scope

- Push、部署、发布，以及父规格明确延期的第二迭代主题。
