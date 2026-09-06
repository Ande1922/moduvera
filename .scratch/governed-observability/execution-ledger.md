# 首批执行台账

## Authorization

2026-09-06：维护者确认 `/private/tmp/governed-observability-wave1-plan-20260906.md` 的首批 01 → 07 执行方案。授权限定文档准备提交、三处分支/worktree、两票编辑/普通提交/真实本地验证、独立双轴评审、专用分支集成及本专题 tracker 更新。冲突停止；不合入 main、不 push/部署、不清理 worktree、不启动 02/14，不改变父 Spec/finding/Product Surface 状态。

## Baseline

- Source head: `89326c0eaff694063d9ef2af905d29afd4a8e0ac`，原规划 head 是其祖先。
- Preparation: 44 个已核对 SHA-256 的文档输入；07 新增 01 blocker、01 已认领，04 失效测试指针已校正。源快照原文保留。
- Integration: `codex/governed-observability-20260906-integration` at `/private/tmp/governed-observability-frontier-20260906/integration`。
- Worker 01: `codex/governed-observability-20260906-01` at `/private/tmp/governed-observability-frontier-20260906/01`，准备提交之后创建并派工。
- Worker 07: `codex/governed-observability-20260906-07` at `/private/tmp/governed-observability-frontier-20260906/07`，01 经评审集成后创建；必须验证 01 提交是 base 的祖先。
- 精确 B0、worker/review base/head、实际 agent ID、命令和运行证据见后续交付记录及外部 execution-state.json。

## Agent execution profile

Worker/fixer、Standards/Spec reviewer 请求 `gpt-5.6-sol` / `high` / `fork_turns=none`；预检请求 `gpt-5.6-terra` / `medium` / fresh context。有效设置若运行时未暴露则记未验证。每个 leaf 只拥有一个 ticket/role/worktree，不创建子代理；follow-up 前核对所有权。

## Evidence

运行证据根目录：`/private/tmp/governed-observability-frontier-20260906/evidence`。含限定输入及冻结操作规则；准备提交后的源码固定点和外部证据独立记录，避免把文档更新当作旧门禁仍有效。准备阶段记录保留；当前票 01、07 均已按下述证据交付，批次最终验收由最终固定提交的外部聚合证据给出。

## Phase 1 — Ticket 01 delivered; ticket 07 released

- B0: `cb6602f2eb9cb9ef73c24ee87d1b6a069b083d6b`。
- 01 implementation: `cb76146eab3035526646b227b83e2c8cd23501a6`；ordinary fixes: `607ea5915275ea1438530151f8e341858af20db6`, `a0c5ab9e9c286abbed00c4af9894767f98be0ca2`。原 writer `/root/worker01_agent_runtime`，同票、同分支/worktree 的修复所有权未变。
- Standards `/root/review01_standards` 与 Spec `/root/review01_spec` 在 B0..`a0c5ab9e9c286abbed00c4af9894767f98be0ca2` 均完成且 clean；最终评审记录：`/private/tmp/governed-observability-frontier-20260906/evidence/01/review-final.md`。请求 Sol/high/fresh 的设置不变，有效运行设置仍未暴露。
- 01 已通过 `git merge --ff-only codex/governed-observability-20260906-01` 快进到专用 integration，无冲突。集成后静态契约 PASS（10 tests），运行制品/代码与已评审固定点相同。完整 `clean install`、6 镜像、Agent/JaCoCo 与真实 HTTP/Kafka/数据库/遥测故障资格证据保留于 `evidence/01-review1-final`，最后分析重放位于 `evidence/01-review2-analysis`。
- 07 已认领，实际 base 是记录本次 tracker 状态的下一普通集成提交；创建 worktree 后从 Git 解析并写入外部 `evidence/execution-state.json`，不预填尚不存在的 SHA。必须验证 01 提交是 07 base 祖先。02/14 只解除阻塞，未启动。
- Main 源码未合入；父 Spec/finding/Product Surface 状态不变。07 之后仍需聚合双轴 Review、Normal Gate 与最终适用 Scenario；最终证据另存，避免再写提交使门禁失效。

## Phase 2 — Ticket 07 delivered; stop before 02/14

- 07 actual base: `37029676c19a74567a54d26ee31ba80416151898`；原 writer `/root/worker07_creation_reader`，工作树与分支沿用上文预留值；已验证 01 最终提交是 base 祖先。
- 07 implementation: `a1eb98223fa5da00e70366197fec81c350927361`；ordinary fix: `69497fdb0c4cf9270ec3bdd8da73a34c44aadc3c`。所有修复保留原 worker、ticket、role、worktree 所有权。
- Standards `/root/review07_standards` 与 Spec `/root/review07_spec` 在 `37029676c19a74567a54d26ee31ba80416151898..69497fdb0c4cf9270ec3bdd8da73a34c44aadc3c` 均完成且 clean；记录：`/private/tmp/governed-observability-frontier-20260906/evidence/07/review-final.md`。请求 Sol/high/fresh，有效模型/effort 未暴露，记未验证。
- `git merge --ff-only codex/governed-observability-20260906-07` 无冲突；集成后 BOM consumer、Kafka mapper 和真实 schema 联合测试 41 tests、exit 0，记录：`/private/tmp/governed-observability-frontier-20260906/evidence/final/integration-focused.json`；Agent 静态契约 10 tests PASS、exit 0，独立记录：`/private/tmp/governed-observability-frontier-20260906/evidence/final/agent-static.json`。
- 两票 metadata 均 resolved，02/14 仍 ready-for-agent 且未实施；父 Spec/finding/Product Surface 状态不变。三处 worktree 保留，未合入 main、未 push/部署或清理。
- H1 状态记录中的四个外部 Markdown 证据链接已改为字面绝对路径，避免仓库将它们作为 snapshot 内部链接校验；证据文件和历史结论保持。
- 本次 tracker 普通提交之后固定最终 base/head，执行聚合双轴评审、Normal Gate 与最终镜像/双拓扑 Scenario。最终回执位于外部 `evidence/execution-state.json` 和交付报告；不预填未运行的 PASS，也不在通过后再写提交使其失效。

## Phase 3 — Gate findings corrected by original ticket 01 writer

- 首次 Normal Gate 在聚合 head `a228740865e44cf4692e5f63357d37f366078ce9` 的 sensitive-content 阶段失败，Maven 未运行。原始回执与正式运行证据完整保留，未豁免或削弱 Gate。
- 原 writer `/root/worker01_agent_runtime` 在原 01 分支/worktree 追加普通提交 `7bec10092a05219c00040a3786e144eb2fcf9ec3`、`237a20180d9b15181e4f48ee9b0b07ed97b97e7e`，修复 fixture credential 输入、继承 export 碰撞、canonical tenant 前置校验和证据留存说明。原 Standards `/root/review01_standards` 与 Spec `/root/review01_spec` 在 B0..`237a20180d9b15181e4f48ee9b0b07ed97b97e7e` 均 clean；记录：`/private/tmp/governed-observability-frontier-20260906/evidence/01/review-gate-fix-final.md`。
- 后续修复以普通本地 merge `1a3942adeb6403600fdf845c8210c0dc6784519b` 纳入 integration，无冲突；前两阶段的快进记录仍为当时操作，不将本次后续集成称作快进。没有 amend/rebase/cherry-pick。07 原提交不变。
- 合并后静态契约 13 tests 及 shell 环境回归 PASS、exit 0，回执：`/private/tmp/governed-observability-frontier-20260906/evidence/final/agent-static-after-gate-fix/receipt.json`。Java/runtime 行为未变，完整真实 qualification 仍注明为凭据输入修复前证据；新增输入 seam 有 HTTP wire、缺失/错误 tenant 与子进程隔离证据。
- 本次 tracker 普通提交后固定新的最终 head；聚合双轴审查、Normal Gate、镜像与双拓扑 Scenario 均须对齐该提交。外部交付报告记最终结果；01/07 保持 resolved，02/14 ready-for-agent 且未启动，父 Spec/finding/Product Surface 状态不变。
