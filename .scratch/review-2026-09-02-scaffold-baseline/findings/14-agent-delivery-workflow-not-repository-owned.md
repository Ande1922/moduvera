Type: finding
Status: confirmed
Severity: major
Area: agent-workflow
Claim: 仓库没有一份规范性的端到端流程，串起方案压力测试/Spec、ticket、隔离并行实施、集成与 Standards/Spec 复审。
Evidence: baseline 的 docs/agents 没有这条执行流水线；docs/grill 与 .scratch Answer 留有历史过程痕迹，但历史记录不构成稳定触发指针或规范流程。关键阶段仍依赖个人 skills。
Verification: 清点 baseline 的 AGENTS.md、docs/agents、docs/grill 与 .scratch 入口，分别列出决策、ticket、worktree 并行、集成和双轴复审的规范来源；区分历史记录与当前 agent 必须遵循的流程。
Planned: .scratch/repository-owned-agent-delivery/spec.md
Verdict: 接受。下个版本把维护者现有的 `grill-with-docs -> to-spec -> to-tickets -> implement/implement-frontier -> code-review -> 最终验收` 链路去个人化并适配为仓库版本，由 Markdown 组合工作流并通过 AGENTS.md 稳定触发；不另造同义的 `moduvera-*` 流程，也不创建大而全的 change-loop skill。在 Review finding 修复后、最终验收与获准 push 前接入仓库拥有的全量质量门禁；Clean Code 位于窄测通过后、正式双轴 Review 前，Maven `clean` 则属于 pre-push 全量门禁。个人 skill 可作为迭代上游和仓库外独立工具，但项目执行不得依赖个人目录。

# 核心 agent 交付流程未仓库化
