Type: spec
Status: ready-for-agent
Fixes: review-2026-09-02-scaffold-baseline/14

# 仓库拥有且可演进的 Agent 交付工作流

## Outcome

把维护者现有的 Matt 风格交付链路去个人化并适配到仓库：`grill-with-docs` 明确需求，`to-spec` 形成规格，`to-tickets` 拆解工作，按任务形状选择 `implement` 或 `implement-frontier`，执行 Standards/Spec 双轴 Review，经过 pre-push 全量质量门禁后完成代码验收。仓库 Markdown 负责组合这些可独立调用的 Skills，同时允许维护者把个人 Skill 的通用改进以可审查方式持续同步进项目版本。

## Confirmed boundary

- 项目交付能力以仓库内多个 `.agents/skills/<skill-name>/SKILL.md` 提供；沿用并适配现有的 `grill-with-docs`、`to-spec`、`to-tickets`、`implement`、`implement-frontier` 与 `code-review` 职责，不另造一套同义的 `moduvera-*` 替代流程，也不复制一个大而全的 `change-loop`。
- 完整链路一次性仓库化，包括 `grill-with-docs`、`to-spec`、`to-tickets`、`implement`、`implement-frontier`、`code-review` 及新增的薄 `quality-gate` Skill；不采用“只有项目差异较大的后半段进仓库、前半段继续依赖个人目录”的过渡形态。
- 完整仓库化包含运行时依赖闭包：至少纳入 `grill-with-docs` 使用的 `grilling`、`domain-modeling`，以及显式选择 TDD 时使用的 `tdd`。支撑 Skill 可以独立调用，但被纳入仓库不代表默认启用；尤其 `tdd` 仍服从风险选择和显式触发规则。
- `docs/agents/delivery-workflow.md` 是组合关系的权威来源，按任务形状连接所需 Skills、仓库文档、质量脚本和 tracker 退出条件；根 `AGENTS.md` 只提供触发该工作流与独立 Skills 的短指针。
- 项目 Skills 去除用户名、绝对个人路径、个人工具偏好以及当前仓库不存在的能力假设。
- 任意开发者或 agent 只检出仓库即可读取完整的强制流程；运行时不得依赖 `~/.codex/skills`、`~/.agents/skills` 或符号链接到个人目录。
- Skills、组合工作流、相关仓库文档和质量门禁一起版本化；项目执行使用当前 checkout 的版本，避免个人 Skill 更新后无审查地改变项目行为。
- 个人 Skill 可以继续作为通用流程的迭代上游，但后续同步保持人工处理；本版本不规定同步方法、不开发同步工具，也不定义 provenance/lock 格式。任何人工同步仍须形成可审查 diff、完成项目适配并通过验证，不能直接覆盖项目定制。
- commit、push、worktree 和其他外部或破坏性操作继续服从用户授权，不由 Skill 扩大权限。
- 测试义务强制，TDD 时序按风险选择。Bug 修复必须先用失败的回归测试复现问题；复杂领域规则、状态转换、幂等、权限和租户隔离等关键行为推荐 test-first，但不作为统一硬门禁；其他行为允许 test-after，但必须在当前 ticket 的 Clean Code、正式 Review 和完成之前补齐。
- `tdd` Skill 不是项目实现阶段的隐式强制前置条件；只有用户明确选择或仓库局部规则要求时才加载完整 TDD 工作流。项目版 `implement-frontier` 不得无条件要求 worker 加载 `tdd`。
- pre-push 覆盖扫描是发现遗漏的兜底，而不是集中补测试阶段。每个 ticket 对自身变更行为的测试证据负责，不得把已知测试债务推迟到全部开发结束以后。

## Proposed evolution model

- 仓库内 Skills 是项目运行时的权威版本；个人 Skills 是改进来源和仓库外独立工具，不是项目运行时依赖。
- 仓库事实、命令、Review Contract、状态规则和门禁细节保留在现有 `AGENTS.md`、`docs/agents/*` 与质量脚本中；Skills 负责各自能力的步骤和完成条件，`delivery-workflow.md` 负责编排顺序与分支。
- 质量门禁的判定逻辑与日志产物由仓库脚本拥有；一个薄的项目 `quality-gate` Skill 只负责选择、运行、解释结果和停止条件，使后续合并维护者既有门禁时无需重写工作流文本。
- 初次仓库化记录个人 Skill 来源和有意保留的项目差异，便于维护者理解这次导入；不把版本、内容 hash、三方比较协议或自动同步机制纳入本版本范围。
- 初次仓库化运行 Skill 结构校验，并用至少一个代表性变更请求做 forward test，确认流程、停止条件和交付证据没有退化。

## Confirmed composition

1. `grill-with-docs`：用逐项决策明确需求、边界、领域语言和需要沉淀的 ADR。
2. `to-spec`：把已确认决策整理为可验收规格。
3. `to-tickets`：按依赖和交付边界拆分 ticket。
4. 实施路由：单个边界清晰的变更使用 `implement`；存在 ticket DAG、并行 worker 或依赖序集成时使用 `implement-frontier`。
5. 每个实现按风险选择测试顺序：Bug 修复 regression-first；关键业务逻辑推荐 test-first；其他行为可以先实现再测试。无论采用哪种顺序，当前 ticket 都必须先取得窄测绿灯，再做一次 Clean Code 整理并重新运行受影响窄测。这里的 Clean 是实现后、正式 Review 前的可读性和结构整理，不进入 red/green 循环。
6. `code-review` 从固定比较点分别执行 Standards 与 Spec 双轴 Review；`implement-frontier` 保留每 ticket 的独立双轴复审，只有跨 ticket 或集成后行为需要检查时才增加一次集成差异 Review，不机械重复同一份 review。
7. Review finding 修复后重新运行受影响窄测；多 ticket 由 `implement-frontier` 按依赖序集成并执行交叉验证。
8. pre-push 阶段调用仓库 `quality-gate`，运行全量扫描和测试。Maven 的 `clean` 属于这一全量门禁（例如 `./mvnw clean verify`），不在每个实现循环前重复执行。
9. 门禁通过后执行最终代码验收；验收若导致任何代码变化，相关 Review 与门禁证据随即失效，必须重跑受影响步骤。
10. 只有用户授权时才 commit 或 push。tracker 更新与关闭由 `delivery-workflow.md` 的退出条件和确定性 checker 约束，不为简单状态写入单独创建 Skill。

## Workflow shape

```text
grill-with-docs -> to-spec -> to-tickets
                                |
                                +-> implement
                                |        or
                                +-> implement-frontier
                                         |
                      narrow green -> Clean Code -> narrow green
                                         |
                         Standards Review + Spec Review
                                         |
                              fixes -> affected checks
                                         |
                           integration / cross-ticket checks
                                         |
                    pre-push quality gate (includes mvn clean)
                                         |
                              final code acceptance -> push
```

## Standalone invocation

- 在本仓库内，即使只单独执行需求澄清、Spec、ticket 拆分、实现、Review 或门禁中的一步，也使用对应项目 Skill，以加载当前 checkout 的仓库契约和命令。
- 个人 Skill 用于其他仓库、跨项目通用工作或作为项目 Skill 的上游实验版本；不得在本仓库交付证据中假设个人 Skill 存在。

## Open design decisions

- 各个人 Skill 需要保留的项目定制清单；已知至少包括移除 `implement-frontier` 对 `tdd` 的无条件加载，落实“测试义务强制、TDD 时序按风险选择”，并以仓库授权、tracker 与验证规则为准。
- pre-push 全量门禁中哪些扫描始终阻断 push，哪些高成本门禁只在合并/发布前运行，等待维护者既有门禁清单后确定。

## Required evidence

- 在不读取个人 Skill 目录的环境中，agent 能从 AGENTS.md 和 `delivery-workflow.md` 选择项目 Skills 并走完一次代表性变更。
- Skills 不包含个人绝对路径、外部未声明依赖或仓库无法满足的隐式工具要求。
- 每个项目 Skill 都能被独立调用并在自己的完成条件停止，不强迫执行后续完整闭环。
- 方案、ticket、实现、Review、验证和 tracker 状态均有明确完成条件，且失败或缺权限时不会伪报 PASS。
- Bug 修复有 regression-first 证据；其他行为无论 test-first 或 test-after，都在当前 ticket 的 Clean Code 与 Review 前提供风险相称的测试证据，不存在集中补测试阶段。
- 普通实现不会无条件加载完整 TDD Skill；显式启用 TDD 时，Clean Code 不混入 red/green 循环，并在清理后重跑相关测试。
- pre-push 门禁运行在最终 Review finding 修复之后，包含一次干净构建；门禁后的代码变化会使已有通过证据失效。
- Skill 通过结构校验和代表性 forward test。
