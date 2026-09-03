Type: issue
Status: ready-for-agent
Blocked by: 01

# 12 — 提供新增 Business Service 的 AI 入口

## Outcome

让脚手架消费者和 agent 从一个受支持入口，根据业务能力的调用、持久化和装配形态组织新 Business Service，而不是复制现有服务并猜测隐含步骤。

## Acceptance Criteria

- [ ] 仓库提供一份人和 AI 共用的权威配方，并从根级 agent 指引和交付工作流稳定可发现；配方解释架构意图，checklist 表达可验证完成条件。
- [ ] 配方覆盖同步直接调用、异步-only 消息、纯内部 Application seam、持久化/无持久化、独立 App/多服务 App 的选择条件。
- [ ] 配方指导 API、Application、Domain、Inbound/Outbound Adapter、service-owned migration、App Assembly、消息身份、架构测试和验收入口，并引用现有 ADR 与专项验证工作流而不复制第二事实源。
- [ ] 项目 add-business-service Skill 读取该配方、识别任务形状并接入现有 spec/tickets/implement 路由，不复制流程且不生成代码。
- [ ] 一个代表性 forward test 从能力描述产出完整的规格与 ticket 形状，能解释每个拟新增模块或文件的需求来源，不产生无消费者空模块。

## Verification

- 从根级入口执行代表性新服务 forward test，覆盖同步/异步、持久化、装配、migration、架构和验收分支。
- 校验配方与 Skill 的链接、结构和引用均可在无个人 Skill 目录时解析。
- 复核输出不以复制 Catalog、Order、Inventory 或示例服务作为默认方案。

## Out of Scope

- Maven archetype、代码生成器、模板复制脚本、IDE wizard 或固定目录生成协议。
