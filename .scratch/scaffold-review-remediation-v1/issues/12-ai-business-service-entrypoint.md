Type: issue
Status: resolved
Blocked by: 01

# 12 — 提供新增 Business Service 的 AI 入口

## Outcome

让脚手架消费者和 agent 从一个受支持入口，根据业务能力的调用、持久化和装配形态组织新 Business Service，而不是复制现有服务并猜测隐含步骤。

## Acceptance Criteria

- [x] 仓库提供一份人和 AI 共用的权威配方，并从根级 agent 指引和交付工作流稳定可发现；配方解释架构意图，checklist 表达可验证完成条件。
- [x] 配方覆盖同步直接调用、异步-only 消息、纯内部 Application seam、持久化/无持久化、独立 App/多服务 App 的选择条件。
- [x] 配方指导 API、Application、Domain、Inbound/Outbound Adapter、service-owned migration、App Assembly、消息身份、架构测试和验收入口，并引用现有 ADR 与专项验证工作流而不复制第二事实源。
- [x] 项目 add-business-service Skill 读取该配方、识别任务形状并接入现有 spec/tickets/implement 路由，不复制流程且不生成代码。
- [x] 一个代表性 forward test 从能力描述产出完整的规格与 ticket 形状，能解释每个拟新增模块或文件的需求来源，不产生无消费者空模块。

## Verification

- 从根级入口执行代表性新服务 forward test，覆盖同步/异步、持久化、装配、migration、架构和验收分支。
- 校验配方与 Skill 的链接、结构和引用均可在无个人 Skill 目录时解析。
- 复核输出不以复制 Catalog、Order、Inventory 或示例服务作为默认方案。

## Out of Scope

- Maven archetype、代码生成器、模板复制脚本、IDE wizard 或固定目录生成协议。

## Answer

- 实现分支：`codex/remediation-v1-12-business-service-entry`，最终提交 `ce572f952714a1c7d63e3143e4a8765b11be920e`，集成提交 `1f8d8c178a3a27f534273a7915595415d032f369`。
- `docs/agents/new-business-service.md` 提供人和 agent 共用的权威配方；根级 `AGENTS.md`、交付工作流与项目 `add-business-service` Skill 提供稳定入口，Skill 读取现有路由表且不生成代码。
- 配方内嵌的 Shape Contract 是同步/异步/internal、持久化、App 与验收拓扑的单一事实源；forward validator 泛化消费该合同并对字段、角色、模板、跨 App 承诺和分支覆盖失败关闭。
- 代表性 forward evidence 保留完整 shape card，生成带 acceptance、test seam、decision、exclusion 和 blocker 的纵向 tickets，并让每个 artifact 追溯到具名 consumer、use case 与 support promise。
- 最终 delivery tests 20/20、validator、common extension、Markdown links、Skill structure、sensitive scan 与固定 base diff 检查全部 PASS；最终 Standards/Spec Review 均为 no findings。
