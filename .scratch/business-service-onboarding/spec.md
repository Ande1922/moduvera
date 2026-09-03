Type: spec
Status: ready-for-agent
Fixes: review-2026-09-02-scaffold-baseline/16

# 新增业务服务的 AI 组织入口

## Outcome

为脚手架消费者和仓库内 agent 提供一个受支持、可独立发现的新增业务服务入口，使 AI 无需复制现有服务并猜测隐含步骤，就能根据服务的调用与持久化形态组织 API、Application、Domain、Adapters、migration、App Assembly 和验证证据。

## Confirmed boundary

- 本版本只确定整体结构、决策分支、必做步骤、验证入口和完成条件，不提供 Maven archetype、代码生成器、模板复制脚本或交互式向导。
- `docs/agents/new-business-service.md` 是新增业务服务的权威配方；根 `AGENTS.md` 和 `docs/agents/delivery-workflow.md` 提供简短、稳定的发现入口。
- `.agents/skills/add-business-service/SKILL.md` 负责读取配方、识别任务形状并接入仓库已有的 `to-spec -> to-tickets -> implement/implement-frontier` 链路，不复制另一套需求、拆票或实施流程。
- 配方同时服务人和 AI：正文描述架构意图和选择条件，checklist 描述可验证的完成条件。
- 新服务不得通过复制 Catalog、Order、Inventory 或 Notes 的全部结构来隐式继承不适用的 HTTP、消息、Local 调用、持久化或拓扑选择。

## Required workflow

1. 明确业务能力、所有者、公开用例和支持的调用方式；区分同步 API、异步命令、事件和纯内部 Application seam。
2. 仅在存在受支持的直接 Local 或 Remote 调用时创建 `<name>-api`；异步-only 能力遵循 provider-owned message contract，不为 Handler 人为增加同步 Java API。
3. 在 `<name>-service` 内组织 Application、Domain、Inbound/Outbound Adapter 与 service-owned migration definition，遵循 ADR 0004、0021、0031、0034 和仓库模块边界。
4. 根据实际语义选择 HTTP、message、persistence 和 Local Adapter；DTO/Mapper 只有在语义、形状、版本或序列化边界确有差异时才创建。
5. 在 `apps/` 的组合根选择并激活所需配置切片、runtime Adapter、migration policy、路由与可信 ExecutionContext 入口；按需求决定是否进入多进程 Golden Path、business-core monolith 或两者。
6. 若新增或修改消息身份，登记 kind/type/destination 并执行 message-contract verification；consumer ID、allowed source、Actor 与 permissions 保持消费者本地策略。
7. 为 Domain/Application、真实基础设施 Adapter、App 装配和关键公开流程提供风险相称的分层测试，并把新模块纳入适用的 architecture rules、Maven reactor 和 acceptance harness。
8. 更新产品能力面、必要 ADR、模块图和运行文档；不得把仅有 POM、空模块或未验证配置宣称为 supported。

## Required evidence

- 从根 `AGENTS.md` 能定位到 `new-business-service.md` 和 `add-business-service` Skill。
- 配方明确列出同步、异步-only、持久化、无持久化、单独 App 与多服务 App 的选择条件，而不是要求复制一个固定模板。
- 配方引用现有 ADR 和专项验证工作流，不把同一规则复制成容易漂移的第二事实源。
- 一个代表性 forward test 能从业务能力描述产出完整 Spec/tickets，并覆盖模块、Adapter、migration、assembly、architecture 和 acceptance 入口。
- 在没有生成器的情况下，agent 能说明每个拟新增文件或模块对应的已确认需求；不产生无消费者空模块。

## Explicitly deferred

- Maven archetype、代码生成器、模板仓库和 IDE wizard。
- 在真实业务系统中重复使用并稳定前，把当前结构固化为可生成的固定目录或类集合。
