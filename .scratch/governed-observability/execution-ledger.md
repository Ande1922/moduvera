# 首批执行台账

## Authorization

2026-09-06：维护者确认 `/private/tmp/governed-observability-wave1-plan-20260906.md` 的首批 01 → 07 执行方案。授权限定文档准备提交、三处分支/worktree、两票编辑/普通提交/真实本地验证、独立双轴评审、专用分支集成及本专题 tracker 更新。冲突停止；不合入 main、不 push/部署、不清理 worktree、不启动 02/14，不改变父 Spec/finding/Product Surface 状态。

## Baseline

- Source head: `89326c0eaff694063d9ef2af905d29afd4a8e0ac`，原规划 head 是其祖先。
- Preparation: 44 个已核对 SHA-256 的文档输入；07 新增 01 blocker、01 已认领，04 失效测试指针已校正。源快照原文保留。
- Integration: `codex/governed-observability-20260906-integration` at `/private/tmp/governed-observability-frontier-20260906/integration`。
- Worker 01: `codex/governed-observability-20260906-01` at `/private/tmp/governed-observability-frontier-20260906/01`，准备提交之后创建并派工。
- Worker 07: `codex/governed-observability-20260906-07` at `/private/tmp/governed-observability-frontier-20260906/07`，01 经评审集成后创建；必须验证 01 提交是 base 的祖先。
- 精确 B0、worker/review base/head、实际 agent ID、命令和运行证据随产生记录，当前不预填提交。

## Agent execution profile

Worker/fixer、Standards/Spec reviewer 请求 `gpt-5.6-sol` / `high` / `fork_turns=none`；预检请求 `gpt-5.6-terra` / `medium` / fresh context。有效设置若运行时未暴露则记未验证。每个 leaf 只拥有一个 ticket/role/worktree，不创建子代理；follow-up 前核对所有权。

## Evidence

运行证据根目录：`/private/tmp/governed-observability-frontier-20260906/evidence`。含限定输入及冻结操作规则；准备提交后的源码固定点和外部证据独立记录，避免把文档更新当作旧门禁仍有效。当前仅准备，未有功能测试、评审、集成或 PASS 声明。
