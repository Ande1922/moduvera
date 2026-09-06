# 执行台账

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

## Phase 4 — Launcher entry boundary closed

- 聚合 Spec 在 `261c534f57209f5df2b4a1ef8a83c634ff887b00` 发现最早的目录解析子进程发生在 credential capture 之前。原 01 writer 追加普通 `851a390190d7ab80815dce97505dc64633bc6c9b` 与 `eadfb0c6c080ff17ab3fbd312bc7afd9cd752f45`；后者同时关闭真实 `bash verify.sh` 入口的无斜杠分支/PATH shadow 回归。
- 原票级 Standards/Spec 在 B0..`eadfb0c6c080ff17ab3fbd312bc7afd9cd752f45` 均 CLEAN，记录：`/private/tmp/governed-observability-frontier-20260906/evidence/01/review-gate-fix-round4-final.md`。修复以普通 merge `5792b138b39fdbe8fb879558f2fb5d4000734396` 无冲突集成；集成 shell 环境契约和 13 项静态测试 PASS，回执：`/private/tmp/governed-observability-frontier-20260906/evidence/final/agent-static-after-launcher-fix/receipt.json`。
- 当前只完成受影响修复和票级审查。此 tracker 提交后固定新的最终 head，聚合审查、Normal Gate、镜像及双拓扑验收仍按相同固定点外置记录；02/14 未启动，三处 worktree 保留，主代码未合入。

## 第二批 — 02 和 14

- 2026-09-06：维护者再次调用项目 implement-frontier 继续实施。当前批次限定已就绪的 02、14；沿用本专题 tracker 更新授权及仓库日常本地 Git 授权，执行独立实现、普通提交、双轴评审、专用分支快进集成与必要验证。完成后停止，不启动后继票；本批未包含主分支合入、push、部署或工作区清理。
- 实际比较 base：`70e9a2711c56835ea28347fa2683618514b3bd08`，已验证首批验收提交 `8accd6e0b96b9c991f58d88b51ba86dd17a45ac4` 是其祖先。主目录的其他未提交变更保留。
- Integration：`codex/governed-observability-wave2-20260906-integration`，工作区 `/private/tmp/governed-observability-wave2-20260906/integration`。
- 02 原 writer：`/root/wave2_worker02_logging`，分支 `codex/governed-observability-wave2-20260906-02`，工作区 `/private/tmp/governed-observability-wave2-20260906/02`，base 同上。请求 Sol/high/fresh，有效设置未暴露，记未验证。
- 14 只读预检由 `/root/wave2_metrics_preflight` 完成，请求 Terra/medium/fresh，有效设置未验证。已有 Observer/registry 装配可复用，主要补全实际指标证据；14 尚未创建 writer/worktree。
- 02 先拥有公共日志组件、BOM/reactor 和相关验证资产；14 从其评审通过的集成结果继续。该安排是执行顺序，不增加票据行为依赖。
- 外部运行结果索引：`/private/tmp/governed-observability-wave2-20260906/evidence/execution-state.json`。原始报告与命令输出按票保存；当前正在实现 02，尚无第二批评审、门禁或最终 PASS。
- 最终按当前 ADR 0038 运行适用的微服务 Scenario，保留单体编译，单体运行验收在本批范围之外。父 Spec、finding 和 Product Surface 不随认领变更状态。


## 第二批阶段 1 — 02 集成，14 接续

- 02 最终代码：`19f8022f3b5efa003bd6f7cade7bd99c09203b45`，base 为本批 `70e9a2711c56835ea28347fa2683618514b3bd08`。原 worker `/root/wave2_worker02_logging` 与同票 Standards `/root/wave2_review02_standards`、Spec `/root/wave2_review02_spec` 完成注册评审闭环，两轴最终均 CLEAN。请求 Sol/high/fresh，有效设置仍未验证。
- 原始报告、两轮修复与最终验证：`/private/tmp/governed-observability-wave2-20260906/evidence/02/`；最终 worker report 为 `review-round2-worker-repair.md`，两轴为 `review-round2-repair-standards.md` 与 `review-round2-repair-spec.md`。点分/混合 API-key 遗漏有修复前 RED 和修复后 GREEN；原异步 appender 发现经契约核对归为未验证的扩展管线，保留复现和 erratum，不把它描述成已实施能力。
- `git merge --ff-only -- codex/governed-observability-wave2-20260906-02` 成功，无冲突。原主体上下文实现经过协调者风险核查，最终 SHA 保持一致。集成 BOM consumer/architecture 的 27 模块通过；沙箱端口失败与自动审批重跑的完整日志/退出码均保留。
- 14 已认领；从本次 tracker 普通提交创建 `codex/governed-observability-wave2-20260906-14`，工作区 `/private/tmp/governed-observability-wave2-20260906/14`。实际 base 与原 writer 在创建成功后填入外部 execution-state，不预填不存在的 SHA。01 与 02 的交付提交必须在其祖先链上。
- 03、04、05、06、08、09、10、15 只解除阻塞，未开始。当前尚无 14 或本批聚合 Gate/Final Acceptance PASS；完整本批最终证据在最终提交冻结后外置保存。源 main 只同步本专题 tracker，代码未合入、未 push、未清理 worktree，父 Spec/finding/Product Surface 不变。
