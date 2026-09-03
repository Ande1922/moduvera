Type: finding
Status: confirmed
Severity: major
Area: agent-workflow
Claim: 本地 tracker 的 spec 终态、issue 完成清单与完成证据存在漂移，无法稳定表达真实进度；旧文件使用加粗 Status 是已允许的兼容格式，不属于本问题。
Evidence: 多份 spec 仍为 ready-for-agent，其中至少四份的所有子 issue 已 resolved；app-assembly-topology issue 03 已 resolved 但四项 checkbox 均未勾且没有 Answer。multi-tenant-order-fulfillment 仍有 blocked issue，证明不能按目录或状态数量批量关闭规格。
Verification: 逐个统计 ready-for-agent spec 的子 issue 状态；读取 app-assembly-topology issue 03 的状态、Answer 和 checkbox；对照 issue-tracker 与 triage-labels 确认旧的 **Status:** 格式无需机械迁移。
Planned: .scratch/hosted-ci-quality-gates/spec.md
Verdict: 接受并合并到托管 CI 与质量门禁 topic。执行一次性状态校准，并增加 tracker 一致性 checker；resolved issue 必须有完成证据且清单不能静默未完成，spec 状态必须与子 issue/blocker 一致。

# Tracker 状态与完成证据漂移
