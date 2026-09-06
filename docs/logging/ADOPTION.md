# Moduvera 日志规范采用与落地状态

本文记录采用决定、评审意见和本仓库的实施边界，不覆盖 [LOGGING.md](LOGGING.md) 的字段、级别或场景语义。运行能力状态仍以 [Product Surface](../implementation/SCAFFOLD-PRODUCT-SURFACE.md) 为准；受治理可观测性仍为 Planned。

## 已确认的采用决定

2026-09-05，维护者确认：以灵脉已经过多轮评审的日志规范为团队默认底稿，没有严重问题就直接采用；局部问题先反馈，再决定是否优化。

- 初次导入 [LOGGING.md](LOGGING.md)、9 个场景文件和 2 个历史决策文件时，12 个文件均与源文档逐字一致。随后经维护者确认，主规范修订了 Trace 与业务关联的语义；9 个场景和 2 个源历史决策仍保持原文。活规则、场景补充与历史决策的效力仍按主规范区分。
- 保留跨语言字段与语义、单行 ECS JSON、记录责任与级别、异常与重试、安全禁令以及 Trace 与身份分离的既有选择。
- 首版直接接入 OpenTelemetry Java Agent。按主规范的 SDK 接管路径实施，由 Agent 提供的 SDK 与自动埋点承担支持范围内的 Trace 生成和传播；不先移植灵脉的自管 Trace 过渡实现。
- 维护者确认当前仍是脚手架打磨阶段，没有实际业务使用，也没有依赖现有 correlation 契约、必须保持兼容的外部消费者。历史消费者兼容不构成本次设计约束。
- 维护者在[改动范围](TRACE-CONVERGENCE-SCOPE.md)审阅中重新检验了“Trace ID 与 Correlation ID 等价”的假设，随后确认：技术追踪统一到 OTel，跨 Trace 的业务关联按需保留；原全面删除 correlation 的方案被替代。2026-09-06 已整体确认接入方案及影响范围，运行实现仍待实施与验收。
- [Java / Spring Boot 绑定](bindings/JAVA-SPRING-BOOT.md) 说明本仓库的映射边界；灵脉的类名、Starter 自动配置和已实现清单不成为 Moduvera 的实现承诺。
- 本次采用的是规范文档。HTTP、消息、任务、Formatter、MDC/Trace 和指标接入尚未实施或验收；导入设备、GenAI 等场景也不新增对应的产品支持声明。

## 来源与可追溯性

来源为维护者指定的 `lingmai-platform/docs/logging/` 在 2026-09-05 的工作树快照。源仓库 HEAD 为 `2df0d40faaf4d8a35b4e65b99f1d831db0bee8ed`，但该目录还有暂存重命名与未暂存修改，因此不能仅用此提交定位导入内容。

[SOURCE-MANIFEST.json](SOURCE-MANIFEST.json) 记录源目录、所有 15 个源文件的 SHA-256 及每个文件的采用方式。修订前的主规范保留为[灵脉日志原文快照](references/LINGMAI-LOGGING.md)，当前活规则使用本地 LOGGING.md。源 Java 绑定原文保留为 [灵脉实现参考](references/LINGMAI-JAVA-SPRING-BOOT.md)；其中的组件与配置描述属于源项目，实际使用本仓库时从本地绑定入口开始。references 中的原始相对链接保持源目录语境，不作为本地阅读入口。源项目的 ADOPTION 与 ROADMAP 由本仓库说明替代。源目录未作修改。

## 评审结论与处理结果

初次文档评审未发现阻碍选定团队默认底稿的严重问题。后续持久化重放讨论发现“整条业务流只靠 Trace”概括过宽，已按下述确认决定修订；这不否定其余日志规则。此结论不是运行实现、安全测试或性能验收结论。

| 项目 | 证据与影响 | 处理 |
|---|---|---|
| HTTP 无状态码时的结果分类 | 源规范曾把无状态码归为 `unknown`，同时又要求已失败的边界尝试为 `failure`，可能导致 DNS/连接超时的绑定口径不一致。 | 已确认并同步主规范：已观察到传输失败为 `failure`，无法判断本次尝试结果才为 `unknown`；真实输出样例待实施验证。这里的调用尝试结果不证明远端业务是否已执行。 |
| Formatter 的可移植性 | 源 Java 绑定依赖 `LingmaiEcsStructuredLogFormatter`，并描述其对原生 ECS 的兼容修复；当前 Moduvera 没有该组件。 | 规则沿用，实现待验证。实施者针对本仓库 Boot 版本验证 `error.code` 与 throwable 同时输出，再决定是否复用该修复；不把源类名写入当前应用配置。 |
| Correlation 与 Trace 的接入 | 当前 [ExecutionContext](../../framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContext.java)、[HTTP Filter](../../framework/starters/moduvera-web-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/web/CorrelationIdFilter.java) 与 [MessageDescriptor](../../framework/foundation/moduvera-message-core/src/main/java/io/github/ande1922/moduvera/message/MessageDescriptor.java) 已有 correlation 契约；其值不保证符合 W3C Trace ID 格式，也未自行定义完整业务会话生命周期。 | 技术追踪与业务关联分工已确认；旧字段不能全面删除、直接改名为 Trace 或原样保留即宣告完成。[范围清单](TRACE-CONVERGENCE-SCOPE.md)已修正；根入口生成、下游继承、执行上下文与持久消息承载已确认；精确字段、版本及历史状态处理见已确认的[整体方案](INTEGRATION-DESIGN.md)。 |
| Actor / Initiator 投影 | Moduvera 区分当前 Actor 与原始 Initiator；主规范的 `user_id` 不能无条件承载 SERVICE 或 SYSTEM，也不能替代两者身份语义。 | 日志只消费可信上下文。字段类型与映射已确认并登记到主规范，不通过复制整个上下文或权限集合解决。 |

## 已确认方向：技术追踪与业务关联分工

以下整体方向已获维护者确认；完整决策经过、未采用方案与证据责任见[决策记录](decisions/TRACE-AND-BUSINESS-CORRELATION.md)。尚未执行运行代码迁移：

- 技术追踪使用 `trace_id` / `span_id`，HTTP 使用标准 Trace Context，诊断响应回写 `X-Trace-Id`。保留 `X-Correlation-Id` 与 Problem 的 `correlationId`，公网生成、内部继承，不再全面删除。
- Correlation ID 独立标识根入口操作及其因果派生工作。新根入口生成、内部调用和消息继承；普通查询根入口也有自身关联，不使用 commandId 或 Trace ID 兼任，不要求与它们值相等。
- ExecutionContext 承载关联元数据并保留可信身份和执行范围，Trace Context 由 OTel 独立管理。消息信封与 Outbox 保存关联；不向 framework-free Kernel 引入 OTel SDK 依赖，不要求给全部业务表加列。字段缺失约束、入口临时载体与协议细节均已整体确认。
- 消息与 Outbox 仍须持久化可恢复的 Trace Context。正常 Outbox 自动发送与重试继续当前发布代次 Trace；人工恢复创建新 Trace 并 Link 原创建上下文，新代次与重投状态原子持久化。同一原消息重投保持 MessageId，仍属于原操作时保持已有业务关联；新业务命令另行区分。
- 保留 `MessageId`、`causationId` 和其他领域键的独立语义。现有 Inbox 用 `descriptor.id()` 去重；本决定不改变去重身份。实施前协调消息 schema、持久化、HTTP/异步验收及[ExecutionContext 传播专题](../../.scratch/execution-context-propagation/spec.md)中的现有 Correlation 模型与隔离断言。

## 整体接入方案已确认

2026-09-06，维护者要求一次整体审阅，并在核对 Trace 存储与改动范围后回复“好的，那就这样定”。[日志接入整体方案](INTEGRATION-DESIGN.md)中的下列默认值已确认，对应活规则与 Java 绑定已同步。运行能力仍为待实施；未据此开票、改运行代码或推进执行状态。

| 范围 | 已确认默认值 |
|---|---|
| 入口与关联 | 公网生成、内部继承；非空与字段格式、内部缺失修复、认证前诊断状态 |
| 持久化与重放 | 不可变 creation、持久化发布代次、临时 attempt；人工 redrive 与新 Trace 原子提交 |
| HTTP 与清理 | 保留 correlation Header/Problem，增加 X-Trace-Id；真实完成时机、身份快照、Servlet/Reactor 清理 |
| 字段与日志结果 | Actor/Initiator 分别投影；传输失败无状态码也为 failure；使用单调计时 |
| 装载与迁移 | 固定外置 Agent；stdout/OTLP/Micrometer 分工；追加数据库迁移、消息兼容性验证 |
| 证据与范围 | 两拓扑、真实数据库与 Kafka、线程隔离、输出与失败验收；后续 to-spec 已整理正式规格与相邻契约，规格待审阅，运行证据仍待取得 |

前面的“库存消息会话复用 commandId，普通查询无会话时省略”方案仍为已替代历史；现行已确认关联由根入口独立生成，并由 ExecutionContext 与持久消息承载。本次确认不重新开启这个已确认选择，也不将其扩张为所有业务表加列。

实施者仍需取得以下证据：锁定版本的 Agent 覆盖与 parent/Link 输出、Boot Formatter 的真实 JSON、真实持久化/重启/隔离测试。生产采集后端与审计事务保证明确留在各自专题，不列为本轮逐项问题。

### 审计能力的专题边界

维护者提出封装通用审计日志能力，并询问是否另开 topic。已确定独立讨论和形成契约；当前仅固化专题边界，尚未创建新任务、Spec 或票。当前日志规范已要求审计独立通道，但这不等于审计能力已存在。

两个专题可复用可信 Tenant / Actor / Initiator 与适用的 Trace、业务关联和领域键；审计记录自身的身份、操作对象/结果、事务提交语义、失败策略、持久化/重放去重、访问与保留规则由审计专题决定。审计完整性不能依赖 Trace 采样或技术日志级别；通道分离不预先要求独立服务或数据库。审计也不因同样使用 JSON 就成为通用 logger 的另一个输出级别。

当前专题保留上述上下文语义接缝，技术日志设计已确认；不代替审计专题确定业务事实、存储和失败保证。

### 接入与验证状态

[受治理可观测性专题](../../.scratch/governed-observability/spec.md) 已按维护者后续 to-spec 请求整理为正式规格，状态为 needs-triage 待审阅，包含 21 条用户故事和 10 类验收接缝；HTTP 既有 Spec/票与 ExecutionContext Spec 已同步共享契约，未新建实施票或修改运行代码。日志底稿和首版直接使用 Java Agent 已确认；[整体方案](INTEGRATION-DESIGN.md)已确认装载、持久化、身份与输出边界，具体 Agent 版本和实际覆盖由实施验证锁定。

Agent 的实际框架覆盖和日志上下文关联需用当前 App 验证；脚手架继续负责规范要求的标准结果日志、可信身份投影与 Agent 未覆盖的项目边界。项目边界的补充埋点复用同一 OTel 上下文与 SDK，不另建 Trace 引擎或重复 tracing bridge。Micrometer 应用指标仍是既有方向，已确认本地/验收导出与采样基线，生产后端与比例仍由环境治理决定。业务 ExecutionContext 的身份与隔离契约继续遵循既有 ADR，不由 MDC 或 Trace 反向建立认证依据。

生产采集平台、告警、SLO、保留策略与部署资产继续按专题原边界处理。详见 [ROADMAP.md](ROADMAP.md)。
