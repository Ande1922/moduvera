# 受治理的日志、Trace 与指标 — 已确认交付图

## Approval and baseline

- 2026-09-06：维护者在本任务确认 17 票粒度、直接依赖和落票；本图记录该决定，不重新定义 [父 Spec](spec.md)。授权止于拆票与 tracker 校验，不开始实施。
- 来源任务：`01a07410-b700-7553-be03-5420a7d8339b`；源交接任务：`01a071c6-3c8e-7d20-9849-cebf58dcef69`。
- 规划 HEAD：`ce1636f82f9652b2b14dd9e146e62c748fbf3233`。正式 Spec SHA-256：`c323372e984d4a62501bdc3b9be104f396bf4fdf850fc74d98a5df8e57c59bb4`；父 Spec 保持 `needs-triage`，本轮不隐式修改或关闭父记录。
- 当前工作树有既存未提交/未跟踪内容；实施票的实际 base 必须在启动时重新核对并包含前置交付，不把本次规划 HEAD 当成所有票同一个可执行基线。

## Tickets and direct dependencies

以下为当前执行状态，后续以各票 metadata 为准。原拆票批准历史保留于 Comments；本批授权新增 01 → 07 的执行依赖，01、07 已完成并集成；02/14 已解除阻塞，其余票按未完成前置阻塞。

| 票 | Blocked by | 发布状态 |
| --- | --- | --- |
| [01 — 固定 Agent 装载与真实 Trace 导出](issues/01-pinned-agent-runtime.md) | None | resolved |
| [02 — ECS 输出与可信上下文投影](issues/02-ecs-logging-and-context-projection.md) | 01 | ready-for-agent |
| [03 — 进程内任务与线程切换隔离](issues/03-in-process-task-context.md) | 02 | blocked |
| [04 — Servlet 请求诊断完整生命周期](issues/04-servlet-request-lifecycle.md) | 02 | blocked |
| [05 — Gateway 响应式请求诊断](issues/05-gateway-reactor-lifecycle.md) | 02 | blocked |
| [06 — HTTP 出站传播与尝试结果](issues/06-http-outbound-diagnostics.md) | 02 | blocked |
| [07 — 消息读取端兼容 creation 扩展](issues/07-creation-envelope-reader-compatibility.md) | 01 | resolved |
| [08 — 逐条消费保留本次投递因果](issues/08-inbound-transport-causality.md) | 02, 07 | blocked |
| [09 — Immediate 一次 ACK 发布诊断](issues/09-immediate-ack-diagnostics.md) | 02, 07 | blocked |
| [10 — Durable 原子追加与双库迁移](issues/10-durable-append-and-trace-migration.md) | 02, 07 | blocked |
| [11 — 历史 publication 先存后发](issues/11-claim-owned-publication-repair.md) | 10 | blocked |
| [12 — Relay 同代续投与故障恢复](issues/12-relay-generation-recovery.md) | 11 | blocked |
| [13 — 人工 redrive 原子接受新代](issues/13-atomic-redrive-generation.md) | 12, 08 | blocked |
| [14 — 九项 Micrometer 指标运行接入](issues/14-micrometer-runtime-baseline.md) | 01 | ready-for-agent |
| [15 — 已有业务事实的记录责任与提交时机](issues/15-business-fact-logging.md) | 02 | blocked |
| [16 — 独立 Notes 消费者接入证明](issues/16-independent-notes-qualification.md) | 04, 08, 09, 13, 14 | blocked |
| [17 — 双拓扑整链与接入说明收口](issues/17-dual-topology-observability-closure.md) | 03, 05, 06, 15, 16 | blocked |

依赖表示行为/验证前置，不代表获得并行写入、worktree 或集成授权。相关 `pom.xml`、消息 Adapter、harness 及共享身份/异常文件存在重叠，执行时由协调者安排单写者；不得为追求并行省略必要前置。

## Current frontier

- 当前批次：**01 → 07**；两票均已评审并集成，07 使用 01 验证锁定的 Agent/API 组合。批次最终结果由固定提交的聚合评审/门禁/Scenario 证据给出。
- 01、07 已完成；剩余可执行前沿为 **02、14**，二者保持 ready-for-agent，按本批授权边界停止，等待后续指令。02 完成可释放 03、04、05、06、15，并在 07 也完成后释放 08、09、10。
- 持久发布主链：10 → 11 → 12 → 13；13 同时要求 08。
- 独立消费者：04、08、09、13、14 → 16；双拓扑收口：03、05、06、15、16 → 17。
- 没有新产品/架构问题或外部 blocker。标准传播器/API 版本存在共享先写者，新增 01 → 07 执行边并获维护者批准。Agent/API 版本、最小 Formatter 和精确 artifact 落点为 Spec 已确认边界内的实施选择，须提供证据；依赖运行失败不允许静默改变设计。

## Slice boundaries

- **读取先于写出**：07 交付两类新读取/校验兼容，生产 creation 由 09/10 启用；不把完整消费诊断 08 作为所有 producer 的不必要前置。consumer-first 不是新消息回退旧严格 schema 的承诺。
- **历史修复先于完整 publish 生命周期**：11 在真实发送前路径持久准备 publication，12 恢复该结果并完成执行 Span/故障窗口。11 不能只交付未使用 helper，12 不能用内存临时根代替持久修复。
- **指标独立**：14 基于既有 Observer 验证装配，无需等 redrive；后续消息改动仍承担九个 meter 的回归义务，不能借 broker.ack 名称重定义计时。
- **相邻 HTTP Problem**：[既有票](../http-problem-contract/issues/01-unify-external-problem-details.md)继续拥有 RFC 9457 字段、校验 errors、状态及媒体类型整体收敛。本轮 04/05 衔接现有错误路径的 C 和诊断生命周期；不复制整票、互设循环 blocker 或修改其状态。共享文件必须协调，未来错误格式实现复用已建立请求状态。
- **相邻 ExecutionContext**：[传播 Spec](../execution-context-propagation/spec.md)保留业务快照/Scope/Platform/AI 范围。本轮 02/03/05 只实现日志接入所需组合，保留原身份、缺失、捕获和恢复约束；实施前核对当前 checkout，其他 worktree 的 resolved 票不是本地运行能力证据。
- **支持边界**：只覆盖当前公开接缝。scheduler/lock 的冻结 Candidate 状态不因任务票提升；独立定时、持久任务、设备、GenAI、长连接不新增平台。生产遥测后端、审计和新业务指标目录继续排除。
- **证据分层**：真实数据库的迁移/提交/CAS、独立 Relay 的重启/故障、Kafka 因果证据随 10–13 交付；16/17 复用准确证据并验证消费者/装配，不把所有难验证行为留给最后一票。

## Story ownership

| Spec story | 主要交付票与整链证明 |
| --- | --- |
| US01 | 02；17 整链 |
| US02 | 02（输出/安全）、03–06/08–13（边界责任）、15（业务事实）；17 整链 |
| US03 | 04、05；17 整链 |
| US04 | 04、05、07；17 整链 |
| US05 | 04、05；17 整链 |
| US06 | 04、05；17 整链 |
| US07 | 02、04、05；16/17 消费证明 |
| US08 | 02、03、05、08；17 整链 |
| US09 | 03（现有进程内接缝）；17 支持边界 |
| US10 | 01、06、09、12；17 整链 |
| US11 | 09；16 独立消费 |
| US12 | 10；15 事实时机 |
| US13 | 12；17 整链 |
| US14 | 13；16/17 整链 |
| US15 | 08、12、13 |
| US16 | 10（升级）、11（修复）、12/13（恢复） |
| US17 | 07（兼容）、09/10（写出） |
| US18 | 08、13 |
| US19 | 14；各消息票回归，16/17 装配 |
| US20 | 01；16/17 装配 |
| US21 | 16、17；02 依赖边界 |

## Test ownership

| Spec seam | 主要交付票 |
| --- | --- |
| TD01 | 02 输出；03–06/08–13 边界责任；15 业务事实 |
| TD02 | 04 Servlet；05 Gateway；17 公共拼接 |
| TD03 | 02 同步组合；03 JDK/任务；05 Reactor |
| TD04 | 09 Immediate；10 append；12 Relay；13 redrive |
| TD05 | 10 双库升级；11 claim 修复；12/13 独立进程恢复 |
| TD06 | 07 两类 schema/mapper/TCK；09/10 写出回归 |
| TD07 | 08 消费；09 生产；12 Relay；13 人工新代消费 |
| TD08 | 14 registry；消息票回归；16/17 实际装配 |
| TD09 | 01 Agent 基线；06 真实 HTTP client；09/12 Kafka；16/17 组合 |
| TD10 | 16 独立 Notes；17 双拓扑与说明；02 公共依赖边界 |

所有条件都是待执行验收义务。功能测试、真实 Agent、正式 Review、Quality Gate 与 Final Acceptance 未在拆票阶段执行；父 Spec、finding 和 Product Surface 不因本图或票数量更新为已完成。

## Comments

- 2026-09-06：按维护者确认的 DAG 创建 17 个 issue 文件。初始前沿 01、07，其余 15 票以真实未完成前置标记 blocked。未修改父 Spec 或相邻 tracker。
- 2026-09-06：拆票文档校验通过：`python3 tools/tracker/check.py` 输出 `PASS (spec=19, issue=97, finding=25, review=2)`；17 票与本图的 233 个本地链接有效，108 个验收项均为未完成，直接依赖逐项匹配维护者确认的 DAG，状态、必需章节和尾随空白检查通过；父 Spec SHA-256 保持上述值。
- 2026-09-06：消息票 07–14 及 16/17 的消息证据消费边界经独立只读文档核对，无可操作问题。此检查不是正式代码 Review；未运行功能测试、Agent/数据库/Kafka 验收或 Quality Gate。
- 2026-09-06：维护者确认首批执行方案；以源码 89326c0eaff694063d9ef2af905d29afd4a8e0ac 加限定文档准备提交为基础，按 01 → 07 顺序隔离实施、双轴评审和专用分支集成，完成后停在 02/14。主目录不合入、不 push、不删除 worktree。

- 2026-09-06：01 的[完成记录](issues/01-pinned-agent-runtime.md#answer)包含固定提交、全量运行及双轴评审证据，已无冲突快进集成；07 按原授权接续，02/14 不启动。

- 2026-09-06：07 的[完成记录](issues/07-creation-envelope-reader-compatibility.md#answer)包含固定提交、真实 schema/模块验证与独立双轴评审；已无冲突快进集成。01→07 代码前沿已交付，02/14 未启动，批次最终聚合验证另存。
