Type: issue
Status: resolved
Blocked by: None

# 02 — 建立可自证的质量门禁核心

## Outcome

提供仓库拥有的单一质量门禁入口，使纯文档变更获得快速且确定的检查，其他或无法分类的变更执行完整 Normal gate，并留下可复现、私有且脱敏的验证证据。

## Acceptance Criteria

- [x] 门禁只暴露自动分类和强制 Normal 两种模式；调用者不能强制选择 docs-only，未知、混合或无法确定的变更会升级到 Normal 或失败关闭。
- [x] docs-only profile 执行本 ticket 拥有的门禁自测、diff whitespace、本地 Markdown 链接、项目 Skill 结构和敏感内容检查，并提供供后续 tracker 与 changed-code checker 接入的稳定步骤边界；Normal profile 还执行当前仓库的干净 Maven Reactor 验证。
- [x] 手工运行要求显式固定比较点，摘要记录解析后的 base、head/ref、选择的 profile 及理由；缺失或不可解析的比较点不会复用旧报告。
- [x] 完整日志与有界脱敏摘要分离，证据目录和日志使用仅当前用户可访问的权限，维护 latest 指针并只保留最近 20 次已完成运行。
- [x] 门禁不改源码、不自动格式化、不跳过测试，也不把历史 JaCoCo 或其他旧产物当作当前通过证据。
- [x] 分类、失败关闭、日志权限、脱敏、保留策略和非零退出均有确定性 fixture 自测。

## Verification

- 用正向与负向 fixture 验证 docs-only、Normal 和未知路径分类。
- 运行门禁脚本自测，并验证失败步骤保持非零退出。
- 对一个干净基线执行 Normal gate，检查摘要、完整日志、权限和保留行为。

## Out of Scope

- 托管 CI job、Mutation/PIT、数值型 coverage/CRAP 阈值和场景测试调度策略。

## Answer

- 实现分支：`codex/remediation-v1-02-quality-gate`，最终提交 `0ab53a6e1d2758ea0867ae20d867081bc4346e92`。
- 最终 Standards Review 与 Spec Review 均为 no findings；门禁明确以可信、已提交仓库步骤为执行边界，不宣称为恶意代码沙箱。
- 门禁自测 64/64 通过，敏感内容扫描通过；官方 Normal 证据位于 `/private/tmp/moduvera-remediation-v1.vOJNbb/02/.quality-gate/runs/20260903T063922.690287Z-72198`，包含精确 `./mvnw -B -ntp clean verify` 的 `BUILD SUCCESS`。
- 集成后重新运行门禁自测与 Ticket 06 三组 harness 聚焦测试，全部通过。
