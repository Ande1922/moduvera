Type: issue
Status: resolved
Blocked by: None

# 01 — 仓库化 Agent 交付工作流

## Outcome

让只持有当前仓库 checkout 的维护者或 agent 能发现并执行从需求决策、规格、拆票、单票或 DAG 实施、双轴 Review 到质量门禁和最终验收的完整交付链，而不依赖个人 Skill 目录或会话记忆。

## Acceptance Criteria

- [x] 仓库提供完整交付链所需的项目 Skills 及其运行依赖闭包，并由一份权威工作流文档组合；根级 agent 指引只保留稳定的发现入口。
- [x] 每个 Skill 可独立调用并在自身完成条件停止；组合流程能按任务形状选择单票实施或 frontier 实施，而不复制另一套同义 change loop。
- [x] 项目 Skills 不包含个人绝对路径、用户名、符号链接到个人目录或仓库未声明的能力假设；在屏蔽个人 Skill 目录的环境中仍可运行。
- [x] 测试义务、风险驱动的 TDD 选择、窄测变绿后的 Clean Code、固定比较点双轴 Review、门禁失效条件和授权边界在各阶段保持一致；普通实现不会无条件加载 TDD。
- [x] 结构校验和一个代表性 forward test 证明 agent 能从仓库入口完成阶段选择、停止条件和证据交付，失败或缺权限时不会伪报通过。

## Verification

- 运行项目 Skill 的结构与链接校验。
- 在不读取个人 Skill 目录的隔离环境中执行代表性 forward test。
- 检查工作流对 commit、push、worktree、tracker 写入等操作仍要求显式授权。

## Out of Scope

- 个人 Skill 与项目 Skill 的自动同步、版本锁或 provenance 协议。
- 托管 CI、代码生成器或单一大型 change-loop Skill。

## Answer

- 实现分支：`codex/remediation-v1-01-agent-delivery`，最终提交 `8c6575dd0104a2731d35cbc6e148e19704072600`。
- 最终 Standards Review 与 Spec Review 均为 no findings；验收证据绑定 `base/head/ref/profile`，个人路径检查覆盖 macOS、Linux root/home 与 Windows home，公共门禁对 validator 和 forward runner 均失败关闭。
- 项目 Skill validator、10 项 agent-delivery forward tests、64 项 gate self-tests 与真实 common extension 全部通过。
- 官方 Normal 证据位于 `/private/tmp/moduvera-remediation-v1.vOJNbb/01/.quality-gate/runs/20260903T084347.539506Z-96328`，包含精确最终 HEAD 与整仓 Maven `BUILD SUCCESS`。
