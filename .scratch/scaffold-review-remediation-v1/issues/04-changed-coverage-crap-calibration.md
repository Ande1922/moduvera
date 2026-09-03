Type: issue
Status: ready-for-agent
Blocked by: 02

# 04 — 增加变更覆盖度与 CRAP 校准报告

## Outcome

让 Normal gate 能基于固定比较点和本次干净构建报告 changed-line coverage 与 changed-method CRAP，并在代表性样本完成前只报告数值、立即阻断不可评分或证据不完整的运行。

## Acceptance Criteria

- [ ] 报告只读取本次 Normal gate 生成的 JaCoCo 和源码证据，并根据固定 base/head 计算变更行与变更方法；缺报告、比较点不可解析或应参与的方法无法评分时失败关闭。
- [ ] 门禁摘要包含可评分范围、变更覆盖度、最高变更 CRAP、被排除项及明确理由，完整细节进入私有证据目录。
- [ ] 领域逻辑、基础设施 Adapter、配置装配、文档/构建四类代表性变更均有可重复的校准样本和结果记录。
- [ ] 校准阶段不以 coverage 或 CRAP 数值阻断，但报告存在性、完整性和可评分性立即阻断；后续阈值必须由单独评审决定。
- [ ] 当前 ticket 的工具和测试不引入 PIT、Mutation 空壳或直接复制其他仓库的固定阈值。

## Verification

- 用受控 fixture 验证新增、修改、删除、无法映射和缺失 JaCoCo 报告的行为。
- 对四类代表性变更运行 Normal gate 并保存校准摘要。
- 验证任何旧报告或不匹配 base/head 的证据都会被拒绝。

## Out of Scope

- 数值阻断阈值、Mutation gate 和把测试责任推迟到门禁后的集中补测阶段。
