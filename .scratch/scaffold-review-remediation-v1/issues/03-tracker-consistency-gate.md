Type: issue
Status: resolved
Blocked by: 02

# 03 — 校准 Tracker 并接入一致性门禁

## Outcome

让本地 Markdown tracker 的状态、阻塞关系、验收清单和完成证据一致可判定，并把一次性校准建立在逐项证据复核上，而不是批量改状态。

## Acceptance Criteria

- [x] checker 校验 Type/Status 合法组合、blocker 引用和解除条件、spec 与子 issue 终态一致性，以及 resolved issue 的 Answer、验证证据和验收项处理情况。
- [x] checker 兼容历史 `**Status:**` 形式，不要求为格式统一机械重写旧 ticket，并对缺失、循环或无效 blocker 失败关闭。
- [x] 一次性校准逐项复核已完成 spec 和存在漂移的 issue，不会因为目录内多数或全部状态看似完成就批量关闭。
- [x] Durable Publication 15 号 ticket 的实际提交与验证证据被复核并补齐；只有证据成立时，16 号性能基线才从 blocked 恢复到正确 intake 状态，否则修正 15 的虚假终态。
- [x] tracker checker 被质量门禁的 docs-only 与 Normal profile 共用，并有覆盖合法、非法和历史兼容输入的 fixture。

## Verification

- 运行 tracker checker 自测和全仓 tracker 扫描。
- 复核所有被校准文件的 Answer、提交、验证和 blocker 证据。
- 确认校准未改变仍有 blocker、未完成验收或新建工作的规格状态。

## Out of Scope

- 实施 Outbox 性能基线或自动关闭未经过人工证据复核的历史工作。

## Answer

实现提交 `d1a2197` 增加仓库自有 tracker checker、合法/非法/历史 fixture
和 common quality-gate 扩展。checker 同时约束状态矩阵、本地与显式外部
blocker、循环/缺失引用、blocked 解除条件、spec/child 终态以及 resolved
Answer、验证证据和未处理清单。

历史校准逐项引用已有提交和测试证据：补齐 17 个旧 resolved issue 的
Answer，修正 1 个遗漏勾选，关闭 4 个所有子票均已终态的 spec；15 号由
`71d3f37`、`2409dc2`、`4dab6fa` 及当前 PostgreSQL/MySQL Relay 测试证据
确认完成，16 号仅解除为 `ready-for-agent`。另将仍引用未完成依赖的 05、
13 号 remediation ticket 校准为 `blocked`，未执行性能基线或批量闭票。

验证通过：tracker fixtures 6 项、全仓 97 个 tracker 文件扫描、quality-gate
回归 65 项，以及消息 Starter 七模块聚焦 `verify`（含 PostgreSQL/MySQL
Testcontainers，35 个消息集成测试）。
