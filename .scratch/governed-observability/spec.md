Type: spec
Status: needs-triage
Fixes: review-2026-09-02-scaffold-baseline/03

# 受治理的日志、Trace 与指标

## Problem Statement

脚手架已有 ExecutionContext、HTTP correlation、消息信封、可靠 Outbox/Inbox 和部分 Micrometer 指标，但这些接缝尚未形成统一可验证的诊断链。仅有 correlation 不能描述真实执行拓扑；仅有 Trace 又不能稳定关联人工重投后的原操作。HTTP 认证前错误、异步终止、线程复用、持久化投递与进程重启也不能只靠线程上的 MDC 自动衔接。

缺少统一记录责任会造成成功事实提前打印、同一失败重复记录、未知身份被猜测、重试与最终失败混淆，以及异常 cause 或自动埋点泄漏敏感内容。现有应用指标需要保留语义并纳入同一装配约束，不能因引入 Agent 再建一套指标或 Trace 导出链。

原问题为独立的 major finding。本规格把维护者已采用的日志规范及 2026-09-06 已整体确认的接入方案转为交付契约；文档采用、设计确认与运行实现分别记录，不能据此关闭 finding 或提升产品支持状态。

## Solution

以 [LOGGING.md](../../docs/logging/LOGGING.md) 作为字段、级别、安全与记录责任的唯一活规范，以 [Java / Spring Boot 绑定](../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 映射当前仓库，以[整体接入方案](../../docs/logging/INTEGRATION-DESIGN.md)说明结构和生命周期。采用 OTel Java Agent 提供的单一 SDK 与自动埋点，框架在已有入口、任务和消息持久化边界补充上下文与执行 Span；业务继续使用 SLF4J。

Correlation ID 独立标识一次根入口操作及其因果派生工作；OTel Trace 描述实际执行关系。公网和独立根入口创建 correlation，内部调用、派生任务与原消息重投继承。Outbox 保存不可变创建 Context 和可持久更新的当前发布代次；自动发送继续当前代次，人工 redrive 原子接受新代次与新 Trace，并 Link 原创建 Context。

所有环境输出同 schema 的单行 ECS JSON 到 stdout，Trace 经 OTLP 输出，应用指标继续使用 Micrometer。框架负责可信字段投影、真实终止时机、响应关联及作用域恢复。正式受治理装配需取得真实 Agent、数据库、Kafka、公共 HTTP 和独立消费者证据。

### 设计与交付状态

- 维护者已确认日志底稿、Agent 路径、独立 correlation、Outbox 持久化、HTTP 信任边界、迁移及整体影响范围；不重新开启逐项访谈。
- `needs-triage` 表示本次整理的正式规格待审阅，不表示设计仍缺信息，也不表示运行实现已开始。下一阶段须在批准规格并明确请求后进入 to-tickets。
- [Product Surface](../../docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md) 的既有支持边界继续生效。导入 Job、设备、GenAI、长连接等场景不会自动新增运行能力。

## User Stories

1. **US01 — 统一日志输出。** 作为服务维护者，我希望开发、测试与生产的应用日志使用同一 ECS JSON 字段契约，每行可独立解析，无重复键，字段类型稳定，未知值省略；已有 SLF4J 写法可直接使用，不需接入第二套 logger 门面。
2. **US02 — 责任、级别与安全。** 作为排障人员，我希望每个实际结束的执行单元只有一条 canonical INFO，每次应记录的可观察边界尝试有一条结果记录；恢复 WARN 与最终 ERROR 各由真实责任方记录，最终 ERROR 带稳定 code 和安全 cause，正文、凭据等禁记内容不会通过任一输出路径泄漏。
3. **US03 — 根操作关联。** 作为 API 使用者，我希望每次公网请求（含查询）得到新 UUID v4 correlation，不采信我自带的 correlation；派生的内部调用和消息沿用该值，Trace/command/message ID 均保持独立职责。
4. **US04 — 内部继承与异常输入。** 作为服务维护者，我希望内部来源由受保护的装配边界决定，有效 correlation 原样继承，缺失或非法只补一次并记录 WARN；诊断信息不能建立身份。消息仍遵守既有必填 correlation 与执行契约，不能在处理中另造关联掩盖错误。
5. **US05 — HTTP 结果关联。** 作为客户端，我希望正常可写响应包含 `X-Correlation-Id` 与 `X-Trace-Id`，本服务 Problem 的 `correlationId` 使用同一请求值，仍保持原生 HTTP 状态与 RFC 9457；Gateway 透明转发下游正文，对异常关联不一致记录断点而不重写业务正文。
6. **US06 — 请求真实结束。** 作为运维人员，我希望 Servlet 同步/异步和 Gateway Reactor 请求在真正完成、失败或取消时记录一次结果，涵盖认证前拒绝、未匹配路由、异常、超时和终止竞争，不能在初次 Filter 返回或收到 1xx 时提前完成。
7. **US07 — 可信身份投影。** 作为安全维护者，我希望 tenant、Actor、Initiator 只来自可信执行上下文，认证前未知字段省略；`user_id` 只表示当前 USER Actor。结束日志保留可信快照，同时实际授权作用域按原规则及时退出。
8. **US08 — 作用域隔离。** 作为框架使用者，我希望本地调用和同一执行单元换线程保持 correlation 与完整 OTel Context，嵌套、异常、取消、平台线程、虚拟线程和 Reactor 回调结束后恢复原状态，不清除上层 MDC，不把一个请求或消息的身份留给下一个。
9. **US09 — 已支持任务边界。** 作为任务调用者，我希望新进程内任务提交前捕获父上下文、实际开始时创建任务 Span，普通回调换线程不重复创建任务 Span；未开始即取消或提交失败不产生虚假的完成日志。独立触发与持久任务遵循 ID04 的语义，但不因此启用新的任务平台。
10. **US10 — 自动出站传播。** 作为维护者，我希望实际 HTTP/Kafka 出站复用 Agent 的标准传播与 Span，框架补充埋点与 Agent 不重复；服务令牌缓存命中仍不发额外网络请求，correlation/Trace 不进入令牌缓存键。
11. **US11 — 一次性发布。** 作为 `ImmediatePublication` 调用者，我希望事务外发送继承当前关联与 Trace，成功以 Broker ACK 为界、失败保留原契约；事务内拒绝且不发送，不额外获得 Outbox、自动重试或人工 redrive 语义。
12. **US12 — 持久追加。** 作为 `DurablePublication` 调用者，我希望业务状态、原消息与 correlation、不可变 creation Context、初始发布代次 0 和 publication Context 在同一正确数据源事务中保存；回滚全部不可见，append 返回不被记录为业务已提交或 Broker 已收到。
13. **US13 — 自动恢复连续性。** 作为 Relay 维护者，我希望首发、自动重试、租约接管、长时间积压及进程重启继续持久化的当前代次 Trace，每次实际执行创建新 Span；父 Span 已结束或跨天不会自行换 Trace，等待期间不保持长 Span。
14. **US14 — 人工重投原子性。** 作为运维人员，我希望仅在原 terminal/token CAS 成功时原子接受新发布代次和新根 Trace，并 Link creation；回滚、旧 token 和并发失败都不改变有效代次。后续重试与重启继续新代，原 C、MessageId、业务字段和累计失败计数保持不变。
15. **US15 — 投递结果真实。** 作为排障人员，我希望区分 Broker 发送结果和 Outbox 状态写回结果；ACK 后宕机或 stale claim 允许按原可靠语义续投，不能宣称完成，也不能编造不可观察的传输重发次数。成功消费过的 MessageId 重投仍被 Inbox 去重。
16. **US16 — 历史数据升级。** 作为部署维护者，我希望 PostgreSQL/MySQL 使用追加迁移保留旧数据；未知 creation 保持未知，有效 claim 下首次发送前初始化并持久化 publication，损坏 Context 修复后复用且不增加 generation，WARN 明示 Trace 连续性已中断。
17. **US17 — 消息兼容。** 作为消息提供方/消费者，我希望两类信封只增加可选 creation 扩展，新读取端兼容旧消息，原 correlation、消息身份与类型语义保持；采用 consumer-first，明确旧严格 schema 不保证读取新字段，不以 mapper 往返代替 schema 兼容证据。
18. **US18 — 消费因果关系。** 作为消费者维护者，我希望业务处理 Span 保留本次 transport 的有效 Agent Context，原 creation 仅作 Link，人工重投不会被旧创建关系拉回旧 Trace；缺少传入 parent 时复用 Agent 已建根，逐条消息独立恢复 C 与本地执行 Actor。
19. **US19 — 指标延续。** 作为运行维护者，我希望既有 Outbox Micrometer 指标保留名称、类型、单位、标签与计数语义，应用装配可观察实际更新；不出现第二套 SDK 指标导出或 correlation/Trace/tenant/message 等高基数标签。
20. **US20 — 可复现装载与故障隔离。** 作为应用装配者，我希望外置 Agent 固定版本并校验 SHA-256，受治理启动缺失或装载失败提前报错；采集后端运行中失联时使用有界异步导出，不阻塞业务事务、不重放业务、不改变 Broker ACK，已有 JaCoCo 注入继续有效。
21. **US21 — 消费者与支持证据。** 作为脚手架使用者，我希望微服务、业务核心单体和独立 Notes 消费者通过公共接缝证明真实日志、Trace、指标及隔离语义；公共组件不会强制所有消费者引入 Servlet、Reactor、Kafka 全部依赖，测试计划不能代替产品支持证据。

## Implementation Decisions

### ID01 — 字段、记录责任与输出

完整字段与九类场景遵循活规范及本地绑定，不复制灵脉类名为本仓库组件。框架自动生成 UTC `@timestamp`、级别/logger/message、进程及服务标识、ECS 版本。关键字段如下；字段未知时省略，不写 null、空串或假 0。

| 字段 | JSON 类型及来源 |
| --- | --- |
| `trace_id` / `span_id` | string，实际有效 OTel Context 的 32/16 位非全零 hex；执行单元日志必须有，启动/库日志可无 |
| `correlation_id` | string，当前根操作已有 C；新根 UUID v4，与 Trace 值及生命周期独立 |
| `tenant_id`、`actor_type` / `actor_id`、`initiator_type` / `initiator_id` | string，可信上下文投影；type 为 USER / SERVICE / SYSTEM |
| `user_id` | string，仅当前 USER Actor 的 ID，不能用 SERVICE/SYSTEM 或原始 Initiator 代填 |
| `event.outcome` | string：success / failure / unknown，表示本次执行结果，不承载 retry/dead-letter |
| `duration_ms` | number，非负单调时钟差转毫秒；请求计完整生命周期，任务计执行，发送计可观察尝试；排队单独观察 |
| `event.action` / `error.code` | string，稳定登记值；前者仅用于已成立业务事实，后者遵循 BIZ_/DEP_/SYS_/ENV_ 分类 |
| `retry.attempt` / `retry.max_attempts` | integer，来自控制实际重试的计数器；不将失败计数、generation 或不可观察网络重发冒充总尝试数 |

canonical 与交互结果按活规范保持 INFO，实际恢复/确定再试记 WARN，耗尽时不再记“将重试”；最终失败无人接盘且需人工介入时，最终处理点记一次 ERROR。ERROR 的 code 与安全 cause 共存，WARN/INFO 不带堆栈。异步发送 callback 同时承担最终处理和结果记录时才适用场景合并例外；当前同步 ACK Outbox 的重试/terminal 由 Worker 决定，底层不能抢先记最终 ERROR。

HTTP 最终 2xx/3xx 为 success，4xx/5xx 为 failure；已观察到 DNS/连接/超时/传输失败时即使无状态也为 failure，仅确实无法判断时 unknown。取消/中断按实际终止原因记录，预设 200 不证明完整响应成功。依赖提交成立的业务事实在提交后打印，纯计算事实按实际成立点打印；这不提供审计持久性保证。

自有数据库/缓存正常调用默认不记 INFO；业务边界按关系分类，不按协议决定。禁止原始 Header、query、SQL 全文、HTTP/消息正文、凭据、权限集合等敏感内容通过 message、结构化字段、完整 cause 或自动埋点输出。长度来自已知值或已有计数，不为日志读取/缓存正文；关闭级别时不提前组装昂贵消息。实现使用 Boot 原生 ECS，只有真实输出证明 code/throwable 等冲突才补最小 Formatter 修正。

### ID02 — Correlation 与 Trace 载体

| 语义 | 载体与约束 |
| --- | --- |
| 根操作 correlation | 认证前请求诊断状态；认证后既有 `ExecutionContext.correlationId`；HTTP `X-Correlation-Id`、消息 `correlationid`、Outbox 既有 `correlation_id` |
| 当前执行 Trace | OTel Context / Span；标准 `traceparent` 与可选 `tracestate`；不进入 Kernel 或业务权限模型 |
| 不可变消息 creation | MessageDescriptor 可选纯值 `creationContext`；信封可选 `traceparent` / `tracestate`；Outbox `creation_traceparent` / `creation_tracestate` |
| 当前发布代次 | claim 结果独立元数据；Outbox `publication_generation`、`publication_traceparent` / `publication_tracestate` |

内部 HTTP correlation 接受 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`，不裁剪、不补零、不转成 Trace。ExecutionContext 保留既有非空及构造校验；消息模型/存储沿用既有非 blank、长度不超过 128 的限制，不借此给旧信封加 HTTP 正则。仅能读取但原本不能进入 ExecutionContext 的旧值，保留原值并走既有契约失败流程，不声称本轮修复历史非法业务关联。

Trace carrier 仅保存标准传播字符串及其大小约束，不序列化 SDK 对象、MDC、身份或权限 baggage。标准传播器解析，不手写正则代替 W3C 解析；无效 Trace 丢弃无效关系，不改变已有 C，也不单凭 Trace 格式把合法业务消息判为 poison。未采样的有效 Context 仍用于传播与日志关联。

信封 creation 与 Kafka transport header 同名 Trace 字段分属不同层：前者记录首次创建，后者表示本次投递。人工重投后二者不同是预期。`message_id`、`command_id`、`causationid`、领域键及原生成规则保持独立，generation 不进入 Inbox 去重键；订单/库存业务表及 Inbox 不统一增加 correlation 列。

### ID03 — HTTP 信任、共享请求状态与响应

公网/内部由受保护的装配与路由声明，不能靠请求自报“内部”Header。Gateway 公网入口创建 C 并向内部注入；直接公开的服务同样遵循根入口规则。公网 correlation 原值既不采信也不记录；内部缺失/非法只补一次并 WARN，不因诊断字段单独拒绝业务，但认证/租户缺失仍按既有安全契约处理。

公网默认接受合法 W3C Trace Context，它只参与诊断；不传播身份 baggage。若未来需忽略公网 Trace，须在 Agent 提取前改变入口/传播器，普通 Servlet Filter 不能被当成撤销既有 server Span 的接缝。

一个请求只建立一份诊断状态，含 C、单调起点、本单元 server Context、可信身份投影快照和一次性完成标记。Servlet 使用 request attribute，Gateway 使用 exchange / Reactor Context；错误处理器读取同一状态，不能分别 UUID 兜底。认证前不伪造 Tenant/Actor ExecutionContext；认证后保存不可变日志快照，不延长授权作用域。

同步请求在最终结果确定时收口；Servlet async complete/error/timeout 与 redispatch、Reactor 实际终止回调共享完成标记。最终日志使用已捕获的本单元 Trace/身份，即使回调线程没有活动 Span。每次回调退出恢复原 ExecutionContext、OTel Scope 及本层 MDC 键，不使用全局 `MDC.clear()`，不跨异步等待持有线程 Scope。

可写响应回写 `X-Correlation-Id` 与 `X-Trace-Id`，不回写完整 traceparent，不新增 Problem `traceId` 正文字段。自产 Problem 的 correlationId 与请求 C 一致；Gateway 下游正文透明。异常下游 C 不一致时保留入口 Header 与原正文并记录传播契约失败；不能为“相等”重写业务正文。响应已提交或客户端断开时不得声称未实际发送的 Header/状态已经送达。

### ID04 — 本地调用、线程与任务

直接本地调用保持当前上下文，不强制每个 Java 方法产生 Span。同一执行单元换线程捕获/恢复完整 Context，不额外开任务 Span；新的进程内任务提交前捕获 C 与父 Context，实际开始时开任务 Span，开始后在可观察终止点记录一次结果并结束。执行前取消/提交失败由提交或取消边界记录实际处置。

独立定时触发每次新 C、新 Trace；显式触发的持久任务继承并保存 C/触发 Context，执行时新 Trace + Link，排队时间不计为执行耗时。原消息恢复使用 ID06 的发布代次规则，Relay poll/接管不是新业务根。上述语义只约束当前支持接缝及未来接入，不新增 Job、持久任务、设备或长连接平台。

与 [ExecutionContext 传播 Spec](../execution-context-propagation/spec.md) 共享捕获时点、缺失值、嵌套与恢复约束；适配层组合业务快照与 OTel，不把 MDC/OTel 引入 Kernel，不顺带实施该专题全部 Platform/Scope/AI 设计，也不将其其他分支的完成记录当成本 checkout 已交付。

### ID05 — 发布保证与 Outbox 三层 Context

遵循 [ADR 0030](../../docs/adr/0030-make-publication-guarantees-explicit.md)：Immediate 为事务外一次性 ACK 发送，Durable 为事务内发布意图，二者不因遥测自动互换。Immediate 事务内拒绝且零发送，失败透传，不新增隐式重试/持久化。

Durable 在正确数据源可写事务内，以短 `outbox.append` INTERNAL Span 捕获不可变 creation；初始 generation 为 0、publication 引用 creation，与业务状态及消息一起提交。append Span 结束只表示追加调用结束，不证明事务提交或 Broker 接收；回滚无已提交成功事实与有效发送唤醒。

每次 Relay 实际执行从数据库恢复当前 publication，创建 `outbox.publish` INTERNAL Span，以该代为 parent 并 Link creation，覆盖发送和完成状态写回，退出即结束。Agent 负责 Kafka producer Span 和 transport header 注入；单次 attempt Context 不回写覆盖 creation/publication。正常首发、自动重试、接管与重启不因延时或父 Span 结束换 Trace，不在等待期保持 Span 打开。

### ID06 — redrive、故障与历史修复

人工 redrive 保持既有 `message_id + TERMINAL + claim_token` CAS 接受点。在同一数据库更新/事务中完成 TERMINAL → PENDING、generation + 1、新无 parent 根锚点 Context 的保存和原有调度/claim/失败状态清理；保留累计失败计数。新根 Link 有效 creation，可附加管理请求 Link；C 仍取原消息，不被管理请求覆盖。根 Span 在数据库决定完成后结束，不等待最终投递。

CAS 失败无有效新代次，回滚状态与 Context 同回滚；提交成功只发送既有无载荷 wake，Relay 不能依赖管理线程、闭包或缓存找回新 Context。连续成功人工恢复形成 T2、T3，各代自动重试分别沿用自身 Trace。creation、MessageId、C、causation、payload、原始时间、tenant、source、destination、partition key 不变。保留 MessageId 不强制已成功消费者再执行，当前行也不提供完整人工操作历史审计。

| 故障边界 | 必须保留的结果 |
| --- | --- |
| ACK 后、标记前宕机 | 同代新 Span 续投，可重复投递，Inbox 原键去重 |
| 发送成功但写回失败/claim 失效 | 发送成功与 Outbox 未完成分别可观察；stale token 不更新状态、代次或 Context |
| redrive 提交后、根 Span 导出前宕机 | 数据库新代可恢复，每次 publish Link creation；不承诺根 Span 已被后端接收 |
| 旧行缺 creation/publication | creation 历史未知；有效 claim 下首次发送前 CAS 初始化并保存 publication，后续复用 |
| 当前 publication 损坏 | 放弃非法父关系，有效 claim 下修复且先持久化再续投；generation 不增加，WARN 明示连续性中断，后续复用 |
| 中断或不可观察底层重发 | 保持原取消/重试处置，不虚构发送次数、失败计数或完成事实 |
| 隔离测试无 Agent/有效 SDK | 不伪造 Trace ID；C 仍可用，但不构成受治理运行验收 |

### ID07 — 消费与消息兼容

消费者保留 Agent 根据本次 transport header 建立的有效 Context，框架单次 Inbox/handler INTERNAL Span 为当前处理的子节点，creation 仅作 Link。缺传入 parent 时复用 Agent 已建立的有效根；仅当前也无有效处理 Context 时补根处理 Span 并 Link 有效 creation，不另造重复 consumer Span。Agent 可能使用 parent 或 Links 表达投递关系，验收须证明因果与数量，不预设所有消费者必须和 producer 同 Trace。

每条消息独立恢复 C、可信 tenant、本地 consumer Actor 和适用 Initiator；逐次业务重试使用处理子 Span。poll 批次不能借第一条消息的 tenant/C/Trace；未来跨消息批量 handler 只能 Link 多个来源、逐条恢复，不能声明虚假共同父节点。producer 认证、consumer 权限、Inbox 事务与重试归属保持既有契约。

EVENT 与 ASYNC_COMMAND 两份 schema、mapper、构造夹具及 TCK 一起适配可选 creation 扩展。沿用当前 MessageType major、correlation 必填及原消息语义；先升级读取/校验端，再启用写出。旧 command schema 的 `additionalProperties: false` 不接受新字段，兼容承诺为新端读旧消息，不承诺新消息回退旧严格校验器。若实际引入必填或语义破坏，按 ADR 0020 新 major，不能静默豁免。

### ID08 — 追加迁移与模块边界

PostgreSQL/MySQL 保留 V1，追加两组可空标准 Trace 字段及非空、默认 0 的 publication_generation，保留历史积压。迁移使用 [ADR 0033](../../docs/adr/0033-separate-migration-definition-from-execution-policy.md) 的显式执行策略，正常启动继续校验，不清库、不自动替换历史 creation。

公共日志 Starter 负责输出、字段映射、可信投影与上下文组合工具；Web/Auth/Gateway 负责各自请求生命周期，消息适配器负责持久化与消费，已支持执行接缝负责任务，App/验证资产负责 Agent 装载。公共组件仅依赖实际需要的日志/OTel API/上下文接缝，不强制全量运输依赖；Kernel 和 message-core 保存协议无关纯值。

遵守 ADR 0004、0021、0031 的 Service API、消息身份与模型转换条件；不因日志新增同步 API、wire 权限、通用 DTO/Mapper 或业务层技术参数。Trace/MDC 不传播数据库连接、事务或授权依据。

### ID09 — Micrometer 指标的本轮基线

沿用已有 `MicrometerPublicationObserver` 的九个 `moduvera.messaging.outbox.` 指标，不新建业务指标目录。下表后缀、meter 类型、量纲与既有标签保持；无 registry 时保留既有可选/no-op 行为，受治理应用需证明 registry 接入。

| 名称后缀 | 类型与量纲 | 既有标签 / 语义 |
| --- | --- | --- |
| `claimed` | Counter，消息数 | 已 claim 消息数量 |
| `publish` | Counter，尝试结果数 | destination、type、result；result 为 published / retry / terminal |
| `broker.ack` | Timer，时长 | destination、type、result；保留 Observer 传入 Duration 的计时语义，不替换成端到端/排队时长 |
| `stale.token` | Counter，次数 | operation |
| `claim.conflicts` | Counter，次数 | claim 冲突 |
| `cleanup.deleted` | Counter，行数 | 清理删除数量 |
| `pending` | Gauge，消息数 | 待发布积压 |
| `pending.oldest.seconds` | Gauge，秒 | 最旧 pending 年龄 |
| `terminal` | Gauge，消息数 | terminal 数量 |

禁止将 C、trace/span、tenant、actor/user、message、原始 URL 或异常文本作为指标标签。保留既有有界路由/类型与枚举标签，不把日志诊断字段全量转成 meter tags。Agent metrics exporter 关闭；继续现有 Micrometer 管道，不另加 tracing bridge/SDK 指标链。本轮不新增公网 metrics 端点，现有 Actuator 暴露边界不扩大；registry 和运行装配证据用于验证，不以此选择生产指标后端。

### ID10 — Agent 装载与导出

Agent 采用固定版本及 SHA-256 的外置制品，由开发/验收启动资产准备，容器只读挂载，运行装配注入参数；公共镜像本轮不内置 Agent，不使用 latest。确切版本、OTel API 依赖组合和摘要由实施者在当前 JDK 26 / Boot 4.1.1 上验证后锁定；保留已有 JaCoCo javaagent 注入。

| 本地/验收配置 | 已确认值 |
| --- | --- |
| 传播 | `otel.propagators=tracecontext` |
| Trace 输出 | `otel.traces.exporter=otlp`，显式 `http/protobuf`，验证专用接收端 |
| 日志输出 | ECS JSON → stdout；`otel.logs.exporter=none` |
| 指标输出 | 现有 Micrometer；`otel.metrics.exporter=none` |
| 采样 | 验收根 Trace 全采样，另测上游未采样传播；正式比例由环境配置 |

正式受治理启动缺失 Agent 或装载错误提前失败；普通库级测试可无 Agent，不能用它证明受治理链路。运行中接收端失联使用有界异步导出，不等待遥测确认才能提交业务，不因遥测失败重放业务或改变 ACK。自动采集同样遵守字段安全禁令。生产 Collector、存储、告警、SLO、保留期与部署资产继续独立。

## Testing Decisions

以下为交付要求，均不是本轮运行通过的证据。复用既有最高稳定接缝，不新增生产业务端点用于测试；真实 Trace parent/Links 及数量以实施锁定的 Agent 组合验收。

| 编号 / 故事 | 最高稳定可观察接缝 | 必须证明的行为 |
| --- | --- | --- |
| TD01 / US01–02、07 | 真实 Boot/Logback stdout 输出与当前日志调用方式 | 单行 JSON、重复键检测、固定类型/省略、code 与 cause 共存；INFO/WARN/ERROR 责任与次数；凭据、正文、query/SQL/cause/自动埋点敏感夹具不泄漏；提交/回滚业务事实时机 |
| TD02 / US03–07、10 | 真实 Servlet 安全/异常链、Gateway Reactor 及现有公开 HTTP 黑盒 | 公网 C 忽略、内部继承/只补一次；合法/非法/未采样 Trace；成功、401/403、未匹配路由、异常、async timeout/cancel 一次完成；双 Header、Problem 与透传异常不一致规则；身份快照和实际终止耗时 |
| TD03 / US08–09 | 现有 Kernel Holder/Snapshot 公开路径，真实 JDK Executor、虚拟线程和 Reactor 调度/订阅 | 不同租户及同租户不同请求交错；提交前捕获、普通换线程不新开 Span、新任务开始才开 Span；嵌套/缺失/异常/取消/拒绝后恢复原 ExecutionContext/OTel/MDC；不依赖固定 sleep |
| TD04 / US11–15 | `ImmediatePublication` / `DurablePublication`、`OutboxStore` / `OutboxAdministration`，生产 JDBC/Transport Adapter 与真实 PostgreSQL/MySQL/Kafka | Immediate 事务拒绝零发送、真实 ACK/失败；Durable 提交/回滚；自动重试同代新 Span；人工 redrive 原子新代及连续两次恢复、旧 token/并发/回滚；ACK 后故障、租约丢失、中断；保持原消息及计数/去重 |
| TD05 / US13–16 | 双数据库追加升级夹具，独立 Relay 进程与数据库/导出端观察 | 旧 schema 带数据升级、不改 V1；缺失/损坏 Context 有效 claim 下先存后发，stale worker 不覆盖，generation 不因修复增加；重启恢复、redrive 提交后导出前宕机；重建 Java 对象不代替进程重启证据 |
| TD06 / US04、17 | `KafkaMessageMapper`、两份发布 schema、固定新旧 JSON 样本、Inbound TCK | EVENT/ASYNC_COMMAND 新端读取旧无 Trace 信封、可选扩展往返与实际 schema 校验；consumer-first 边界；既有 C/身份/业务语义不收紧；仅 Trace 非法不成为业务 poison |
| TD07 / US15、18 | 真实 `StreamBridgeMessageTransport → Kafka → ReliableInboundEndpoint.handle` 与导出 Span/record headers | 信封 creation 和当前 transport 分别取证；人工重投消费不回旧父链；缺 parent 复用 Agent 根；局部重试子 Span、逐条恢复、本地 Actor、无串线和重复同语义 Span；DUPLICATE 不再执行业务 |
| TD08 / US19 | 既有 `MicrometerPublicationObserverTest` 的 registry 接缝及真实 App registry 集成 | 九个 meter 的名称/类型/单位/标签及成功、retry、terminal、stale/claim/cleanup、积压语义；禁用高基数字段；无 registry 可选行为，无重复 SDK 指标输出，不扩大管理端点 |
| TD09 / US10、20 | 实际启动脚本、外置 Agent、真实 HTTP/Kafka 和验证 OTLP 接收端 | 固定版本/摘要/显式装载；缺制品失败；单一 SDK 与 Span owner；Gateway→Identity、Order→Catalog/Identity 出站；缓存命中无额外请求；未采样有效 ID；后端失联业务完成、有界导出；JaCoCo 保持 |
| TD10 / US21 | 现有双拓扑公开验收、独立 Notes 消费者、Maven/架构检查与接入说明 | 同一公共行为覆盖微服务和业务核心单体；Notes 独立消费证明真实链路，Kernel 无框架依赖、非使用者无运输依赖膨胀；支持声明与启动/输出证据对应 |

### 当前可复用资产与证据边界

- HTTP：既有 [公开黑盒测试](../../verification/acceptance/tests/test_reference_product.py)及[双拓扑 harness](../../verification/reference-product/harness/verify.sh)，配合各 Servlet/Gateway Adapter 测试；既有 HTTP assertions 尚不证明新增的 Trace/异步完成语义。
- 上下文：既有 [HolderTest](../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextHolderTest.java) 与 [SnapshotTest](../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshotTest.java)，新增组合传播须验证实际线程/回调内部及退出后状态。
- 消息：既有 [PublicationAdaptersTest](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/PublicationAdaptersTest.java)、[JdbcMessagingStoreIT](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingStoreIT.java)、[JdbcMessagingMySqlIT](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/JdbcMessagingMySqlIT.java)、[KafkaMessageMapperTest](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/KafkaMessageMapperTest.java) 和 [ReliableInboundEndpointTest](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/ReliableInboundEndpointTest.java)提供事务、身份、重试与清理接缝；新增 Context 字段、代次与真实 Agent 输出仍待扩展。
- 升级与指标：既有 [MessagingTenantMigrationIT](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/messaging/kafka/migration/MessagingTenantMigrationIT.java)目前证明 tenant 迁移，不证明 Trace 升级；既有 Observer registry 测试不证明 Agent 装配无重复指标。
- 异步负例：沿用 [InboundMessageContractTck](../../framework/testing/moduvera-test-support/src/main/java/io/github/ande1922/moduvera/testing/messaging/InboundMessageContractTck.java) 与[消息验证工作流](../../docs/agents/message-contract-verification.md)。无可信身份、非法业务信封等在 Application 前拒绝，断言库存/订单/Outbox 无副作用时，发送前取 `ProgressBarrier`，等待该消费链的 offset/Inbox 标记或唯一同 partition barrier 前进再比较；未消费必须超时，旧计数、固定 sleep 和发送 ACK 均不替代消费进度证据。
- 基础设施语义按 [ADR 0034](../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md) 使用生产运行 Adapter 与真实数据库/Kafka；mock 可用于格式和编排单元测试，不能证明提交、ACK、CAS、租约或跨进程恢复。各场景同时核对 C、Trace/Span、Actor/Initiator 和 tenant，不能只断言一个 ID 相同。

实施按[交付标准](../../docs/agents/delivery-standards.md)做风险相称验证，保留适用双拓扑 Scenario 和独立消费者义务；本规格不启动 TDD、实现、正式 Review 或 Quality Gate。

## Out of Scope

- 生产 Collector、Grafana/Tempo/Prometheus 等采集存储平台、告警阈值、SLO、retention、生产部署资产和正式采样比例。
- 通用审计能力的记录模型、持久性、事务保证、失败策略、权限、保留与部署形态；当前仅保留共享身份/关联语义，不创建审计 Spec 或票。
- 新业务指标目录、业务实体统一 correlation 列、独立遥测 SDK/Trace 引擎、日志二次门面、公网 metrics 端点。
- 改写订单/库存状态机、授权与租户隔离、Inbox 去重键、消息/命令 ID 规则、发布保证或消费事务与重试归属。
- 平台消息、完整 Platform/Scope/Spring AI 传播、任意 ThreadLocal 自动复制、新任务调度/持久任务平台、设备/GenAI/长连接产品支持提升。
- 本轮运行代码、拆新票、commit/push/merge、部署、finding 关闭和产品状态提升。

## Further Notes

### 未决事项与实施证据责任

没有需要维护者重新决定的产品或架构分歧。以下为已确认边界内的实施选择与证据责任，不能因依赖尚未锁定把规格退回无限访谈，也不能静默改变范围。

| 事项 | 责任与证据 | 需要重新决策的条件 |
| --- | --- | --- |
| Agent/API 版本与摘要 | 实施者在 JDK 26 / Boot 4.1.1 锁定组合，提交 TD07/09/10 的真实 header、parent/Links、数量、启动证据 | 兼容性要求改变已确认装载方式、传播或产品边界 |
| Boot ECS 兼容与安全输出 | 实施者先取 TD01 原生输出，只有实际冲突才补最小 Formatter | 无法在既定字段/安全契约内解决，不能静默丢字段或 cause |
| 物理 artifact、类名与接缝落点 | 实施者按依赖边界选择最小实现，保留既有公开行为与 TD03/10 | 必须新增或改变公共能力契约，而非单纯调整名称 |
| 迁移与历史 Context 修复 | 实施者提交 TD04–07 真实双数据库/进程/消息证据 | 必须改变消息 major、持久化接受点或可靠交付保证 |
| 支持状态与后续交付 | 后续验收者以准确 checkout/commit、双拓扑与独立消费者证据更新 Product Surface/finding | 不能用本规格、源项目组件或其他 worktree 的完成记录代替当前交付 |

### 来源与相邻工作

- 权威设计：[日志活规范](../../docs/logging/LOGGING.md)、[整体接入方案](../../docs/logging/INTEGRATION-DESIGN.md)、[本地 Java 绑定](../../docs/logging/bindings/JAVA-SPRING-BOOT.md)；可追溯过程见[决策记录](../../docs/logging/decisions/TRACE-AND-BUSINESS-CORRELATION.md)、[采用状态](../../docs/logging/ADOPTION.md)、[源文件摘要清单](../../docs/logging/SOURCE-MANIFEST.json)和[改动范围证据](../../docs/logging/TRACE-CONVERGENCE-SCOPE.md)。原全文删除 correlation 的方案已被替代，不复活历史建议。
- [HTTP Problem Spec](../http-problem-contract/spec.md)及其已有票同步公网生成/内部继承和透传异常规则；它继续拥有 RFC 9457 字段、校验 errors、状态及媒体类型收敛。本专题拥有入口诊断状态、Trace Header 和生命周期，不复制业务错误语义。
- [ExecutionContext 传播 Spec](../execution-context-propagation/spec.md)拥有业务快照/Scope 与可选适配契约；本专题在适配层组合 OTel/日志投影，保留其身份、缺失值、捕获和恢复规则。共享接缝实施前按当前 checkout 协调，不把一方全部范围当成另一方依赖。
- ADR 兼容基线包括 [0004](../../docs/adr/0004-use-one-service-api-for-local-and-remote-calls.md)、[0011](../../docs/adr/0011-use-native-http-semantics-and-service-owned-api-versions.md)、[0018](../../docs/adr/0018-group-lightweight-contracts-in-moduvera-kernel.md)、[0020](../../docs/adr/0020-version-message-contracts-in-message-type.md)、[0021](../../docs/adr/0021-validate-inbound-message-contracts-without-wire-permissions.md)、[0030](../../docs/adr/0030-make-publication-guarantees-explicit.md)、[0031](../../docs/adr/0031-map-at-adapters-only-for-semantic-differences.md)、[0033](../../docs/adr/0033-separate-migration-definition-from-execution-policy.md) 与 [0034](../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。编号不预占；若实施需新增决策按实际 ADR 序列处理。

## Comments

- 2026-09-06：依据维护者已整体确认的接入方案，将早期 needs-info 占位更新为完整正式规格；包含 21 条故事、10 类验收接缝、迁移和相邻工作边界，状态为 needs-triage 待规格审阅。
- 2026-09-06：本次仅整理规格及相邻文档，未新建实施票、修改运行代码、取得运行验收证据、提交或提升产品状态。已存在的测试列为可复用资产，不记为新增能力已通过。
- 2026-09-06：文档验证通过：`python3 tools/tracker/check.py` 为 PASS（19 specs、77 issues、25 findings、2 reviews）；本轮 9 份文档的 132 个本地链接、必需章节、US/ID/TD 编号、尾随空白及 13 份保留源快照 SHA-256 检查通过。未运行功能测试或正式 Quality Gate。
