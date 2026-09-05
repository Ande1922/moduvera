# ExecutionContext Ticket Map

用户已于 2026-09-05 确认本图的 11 票粒度与依赖。本轮仅发布 tracker 文件，不实施、提交或自动更新父规格状态。

规格：[spec.md](spec.md)；决策来源：[interface-draft.md](interface-draft.md)。父规格仍保留 `needs-triage`，这是本轮明确不修改父文件的结果，不撤销用户对设计及本 DAG 的批准。

## 基线与执行规则

- 发布时代码 HEAD：`ce1636f82f9652b2b14dd9e146e62c748fbf3233`。规格是已批准的本地文件，SHA-256：`bb1faca70eeaaeb10fbb7923a9a44b0a311be0c315778cbdb3b13631d4078f62`；不得把它误称为该提交中已有的内容。
- 每票实施基线为上述代码基线或经核对的后续基线，加上该票所有直接及传递前置的已集成输出；实施时记录实际 base/head。其他分支的输出、未完成后续票和无关工作树改动不能作为隐含前提。
- 01 在已有 Tenant 模型上即可证明恢复内核；02 增加 Platform 三态与租户资源证据。03 的完整验收使用 02 的三态模型，04/05 使用 03 的具名绑定。
- `ready-for-agent` 仅用于无未完成阻塞项的票；当前只有 01。02–11 为 `blocked`，前置完成后按真实状态解锁；无需等待同一拓扑层的其他票。依赖满足不等于自动获得实施、提交或集成授权。
- 每票交付本场景的实现、可观察测试、接入/生命周期说明和必要依赖检查；不得把本应随票完成的测试拖到 11。新增公共接缝须有消费者调用证据，可复用现有消费者，不要求每票新建示例产品。
- 所有传播测试断言整个 ExecutionContext，包括 Scope、Actor、Initiator、Correlation；覆盖不同租户和同租户不同请求。01 尚无 Platform 类型时验证现有完整上下文及缺失，02 起验证三态。
- 真实基础设施语义依照 [ADR 0034](../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)；上下文传播不复制事务、连接、MDC、Trace 或 Spring SecurityContext。不得把示例/测试计划存在记为运行通过。

## 已批准 DAG

| 票 | 交付行为 | 直接前置 | 发布状态 |
| --- | --- | --- | --- |
| [01](issues/01-safe-scope-and-snapshots.md) | 安全 Scope 与完整快照恢复 | 无 | ready-for-agent |
| [02](issues/02-platform-tenant-and-resource-guards.md) | Platform/Tenant 模型与租户资源保护 | 01 | blocked |
| [03](issues/03-request-bound-callbacks.md) | 请求归属的具名回调绑定 | 02 | blocked |
| [04](issues/04-jdk-executor-propagation.md) | JDK 执行器传播 | 03 | blocked |
| [05](issues/05-spring-task-executor-propagation.md) | Spring TaskExecutor 接入 | 03 | blocked |
| [06](issues/06-http-execution-boundaries.md) | HTTP 租户与平台入口闭环 | 02 | blocked |
| [07](issues/07-reactor-context-templates.md) | Reactor 订阅上下文模板 | 02 | blocked |
| [08](issues/08-ai-request-and-stream-response-context.md) | AI 请求注入与流式响应消费 | 02 | blocked |
| [09](issues/09-ai-tool-loop-context.md) | AI 多轮工具调用隔离 | 08 | blocked |
| [10](issues/10-tenant-only-message-compatibility.md) | tenant-only 消息边界兼容 | 02 | blocked |
| [11](issues/11-composition-and-consumer-qualification.md) | 跨边界组合与消费者资格验收 | 04、05、06、07、09、10 | blocked |

AI 08/09 不以 07 为阻塞项：原生 Reactor 库依赖不等于依赖本项目的 Reactor helper。09 依赖 08 的共用请求注入与保留键契约，不是依赖响应 mapper 本身。07、08 不依赖自有 Executor 装饰器或 HTTP 接入。

## 初始可执行前沿

当前仅 **01**。01 完成后解锁 02；02 完成后可分别推进 03、06、07、08、10；03 完成后解锁 04/05，08 完成后解锁 09；11 在自己的全部前置完成后进入前沿。这是依赖说明，不是整批串行等待规则。

## 规格覆盖归属

| 规格故事 | 主要交付票 |
| --- | --- |
| US01、US02、US05 | 02；01 提供恢复基础 |
| US03、US04、US06、US07 | 01、03；04/05 验证实际执行器行为 |
| US08 | 04、05 |
| US09、US10、US11 | 03、04；01/05 保证实际执行生命周期 |
| US12、US13、US14 | 06；02 提供范围与资源边界 |
| US15、US16 | 07 |
| US17、US19 | 08；09 追加工具循环后的响应 |
| US18 | 09 |
| US20 | 02 的 Job/Lock、10 的消息边界 |
| US21 | 02 的调用兼容、03–10 各自的依赖/消费者检查 |
| US22 | 各票的说明与场景证据；11 组合与资格汇总 |

| 规格验收接缝 | 交付票 |
| --- | --- |
| T01 | 01、02、03 |
| T02 | 04、05 |
| T03 | 03、04 |
| T04 | 06 |
| T05 | 07 |
| T06 | 08、09 |
| T07 | 02、10 |
| T08 | 02–10 各自交付；11 汇总依赖闭包 |
| T09 | 各场景票提供公共消费者证据；11 组合与支持范围核对 |

## 文档与共享文件归属

- 01 建立一个传播 ADR，记录恢复、空快照和捕获时点，并明确已确认但后续票才实施的范围；编号在实际写入时分配。
- 02 同步 CONTEXT.md、ADR 0003/0035 中 Platform/Tenant 与授权边界的已确认修订，包含 Job/Lock 范围区别。
- 03–10 随场景完善同一传播 ADR 与对应接入说明，区分已验证能力和后续承诺，不各建一份重复 ADR。
- 11 只补跨场景说明与证据索引；根据真实证据精确维护产品支持面，不弥补未完成前置或自动关闭父 spec/finding。
- 多张可并行票若接触同一 POM、BOM、ADR 或消费者装配，由获授权的集成者串行合并该共享表面；共享文件写入协调不是凭空新增的业务依赖。精确 artifact 名称及版本随首次使用该依赖的票给出实证，不拆成只有 POM 的前置票。

## 验证与授权边界

本次仅执行 tracker、Markdown 链接/格式和 DAG 一致性校验；功能测试、依赖兼容、独立消费者和 Scenario 均是后续实施要求。本轮不改变源代码、ADR、产品支持状态、父 spec 或原 finding。

发布后验证：11 张 issue 的类型/状态/阻塞关系与批准 DAG 一致，无环，初始前沿为 01；必需章节、90 项未完成验收项、22 条故事和 9 类测试接缝引用检查通过。共检查本目录 14 份 Markdown，本地链接和尾随空白均通过。父 spec 与草案 SHA-256 与写入前一致。

全库 `python3 tools/tracker/check.py` 仍返回 1，唯一报告为未修改的 `.scratch/http-problem-contract/issues/01-unify-external-problem-details.md` 缺少 `Blocked by`。本功能没有新增该检查器错误，但这不是全库 PASS。该问题不是本功能的技术前置，不塞进 DAG，也不擅自修订。

## Comments

- 2026-09-05：用户在 11 票提案后回复“可以”，授权按所示粒度及边落盘；未授权实施。按校验器的实际依赖状态规则发布 01 ready-for-agent、02–11 blocked，父 spec 和草案保持原样。
- 2026-09-05：只读复核后明确 08 必须验证真实流式订阅，并将 10 的当前上下文要求限定到业务消息构建入口；后台 Relay 继续可在没有原请求 Holder 时投递合法持久化消息。未改变批准 DAG 或首期边界，未运行功能测试。

- 2026-09-05 implementation: ticket 01 integrated at `0226e17cfbc48fdd2af42a2b132f438f5eb94177`, both review axes clean; verification PASS. Current open frontier: 02. Claims: 02. See [ticket evidence](issues/01-safe-scope-and-snapshots.md#answer). Historical publication states above remain the original snapshot.

- 2026-09-05 implementation: ticket 02 integrated at `4313748aac07b06943072f7d00f71ce8af5751d0`, both review axes clean; verification PASS. Current open frontier: 03, 06, 07, 08, 10. Claims: 03, 06, 08. See [ticket evidence](issues/02-platform-tenant-and-resource-guards.md#answer). Historical publication states above remain the original snapshot.

- 2026-09-06 implementation: ticket 03 integrated at `8f4cace9078dcb55b737d61ff7ec830f68fe3c6f`, both review axes clean; verification PASS. Current open frontier: 04, 05, 06, 07, 08, 10. Claims: 04. See [ticket evidence](issues/03-request-bound-callbacks.md#answer). Historical publication states above remain the original snapshot.

- 2026-09-06: ticket 10 claimed for isolated message compatibility work; no new dependency edge.

- 2026-09-06 implementation: ticket 08 integrated at `5e80a8dcbdc44262098d2cef3771f1eeb813fa86`, both review axes clean; verification PASS. Current open frontier: 04, 05, 06, 07, 09, 10. Claims: 07, 09. See [ticket evidence](issues/08-ai-request-and-stream-response-context.md#answer). Historical publication states above remain the original snapshot.

- 2026-09-06 implementation: ticket 04 integrated at `8a61f7cafa9dc2c509a18818d9b8f867fb3e6d8e`, both review axes clean; verification PASS. Current open frontier: 05, 06, 07, 09, 10. Claims: none. See [ticket evidence](issues/04-jdk-executor-propagation.md#answer). Historical publication states above remain the original snapshot.

- 2026-09-06 implementation: ticket 06 integrated at `28d0644cec7dd263843113386fe06f560b502055`, both review axes clean; verification PASS. Current open frontier: 05, 07, 09, 10. Claims: none. See [ticket evidence](issues/06-http-execution-boundaries.md#answer). Historical publication states above remain the original snapshot.

- 2026-09-06 implementation: ticket 07 integrated at `8d7a7f3b8ac6aa6402f747a4e49673183d47b231`, both review axes clean; verification PASS. Current open frontier: 05, 09, 10. Claims: 05. See [ticket evidence](issues/07-reactor-context-templates.md#answer). Historical publication states above remain the original snapshot.
