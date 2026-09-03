Type: issue
Status: resolved
Blocked by: 02

# 04 — 增加变更覆盖度与 CRAP 校准报告

## Outcome

让 Normal gate 能基于固定比较点和本次干净构建报告 changed-line coverage 与 changed-method CRAP，并在代表性样本完成前只报告数值、立即阻断不可评分或证据不完整的运行。

## Acceptance Criteria

- [x] 报告只读取本次 Normal gate 生成的 JaCoCo 和源码证据，并根据固定 base/head 计算变更行与变更方法；缺报告、比较点不可解析或应参与的方法无法评分时失败关闭。
- [x] 门禁摘要包含可评分范围、变更覆盖度、最高变更 CRAP、被排除项及明确理由，完整细节进入私有证据目录。
- [x] 领域逻辑、基础设施 Adapter、配置装配、文档/构建四类代表性变更均有可重复的校准样本和结果记录。
- [x] 校准阶段不以 coverage 或 CRAP 数值阻断，但报告存在性、完整性和可评分性立即阻断；后续阈值必须由单独评审决定。
- [x] 当前 ticket 的工具和测试不引入 PIT、Mutation 空壳或直接复制其他仓库的固定阈值。

## Verification

- 用受控 fixture 验证新增、修改、删除、无法映射和缺失 JaCoCo 报告的行为。
- 对四类代表性变更运行 Normal gate 并保存校准摘要。
- 验证任何旧报告或不匹配 base/head 的证据都会被拒绝。

## Out of Scope

- 数值阻断阈值、Mutation gate 和把测试责任推迟到门禁后的集中补测阶段。

## Answer

- 实现分支：`codex/remediation-v1-04-changed-coverage`，最终提交 `8de5d7bf9c6c801194ae039f1eaa87190ae237bb`，集成提交 `bb737affea0760b73b072c578ce33ec4215cecf5`。
- Normal gate 在 Maven 成功后固化并摘要绑定源码、JaCoCo XML 与 class 工件；analyzer 仅消费 no-follow 捕获快照，并对缺失、陈旧、替换、符号链接、base/head 不匹配和不可评分证据失败关闭。
- 公开摘要有逐扩展与总量边界，拒绝终端控制字符；私有证据保存完整映射、hash、覆盖度、CRAP 和排除原因，数值保持 report-only。
- 真实 Maven/JaCoCo fixture 已让 Domain、Persistence Adapter、Configuration 与 docs/build 四类样本穿过 Normal 入口，并覆盖构造器、重载、lambda 与构建后工件变异。
- 最终质量门测试 80/80、敏感扫描、固定 base diff 检查和官方 Normal gate 全部 PASS；最终 Standards/Spec Review 均为 no findings。官方证据：`/private/tmp/moduvera-remediation-v1.vOJNbb/04/.quality-gate/runs/20260903T142410.583253Z-80804`。
