# 日志规范

适用于全部服务，与实现语言无关。本文是平台日志的**唯一活规范**：规定何时记录、由谁记录、使用什么级别以及字段契约。

## 规范效力

- **必须 / 禁止**：强制要求；不满足即不合规。
- **可以**：按场景选择；选择后必须遵守相关字段和级别规则。
- [场景文件](#8-场景)只能补充场景字段、logger 与生命周期，不能覆盖本文。
- [语言绑定](#9-语言绑定)是实现 Adapter，只能映射框架写法，不能改变本文语义。
- [决策、落地状态和路线图](#10-非活规则)不是活规则。

## 写业务代码时读

| 任务 | 必读内容 |
|---|---|
| 写或 Review 一条业务日志 | 本文[打印决策](#1-打印决策)、[字段契约](#2-字段契约)、[异常与重试](#5-异常与重试)、[消息与安全](#6-输出消息与安全)，再读对应语言绑定的消息写法 |
| 实现 Filter、拦截器、包装器或最终处理点 | 本文全文 + 对应[场景文件](#8-场景) + [语言绑定](#9-语言绑定) |
| 排查问题 | [标准排查路径](#7-标准排查路径) |
| 修改规范 | 本文全文，并核对场景、绑定、决策与路线图 |

本仓库当前使用 [Java / Spring Boot 绑定](bindings/JAVA-SPRING-BOOT.md)，HTTP 入站基础设施还需读取 [INBOUND-REQUEST.md](scenes/INBOUND-REQUEST.md)。当前落地进度见 [ADOPTION.md](ADOPTION.md)。

## 1. 打印决策

下表中的“角色”只用于判断，不增加日志字段。每条拟记录有一个主要角色；同一执行过程可以触发多行，由各责任方分别记录。只有场景明确规定同一 Adapter 同时是最终处理点时才可合并。不属于任何一项就不记录。

| 条件 | 谁记录 | 输出 | 条数 | 必有内容 |
|---|---|---|---:|---|
| 执行单元结束 | 场景基础设施 | canonical，INFO | 每个完成的单元 1 条 | `event.outcome`、`duration_ms`、场景字段 |
| 一次业务边界交互的实际尝试结束 | 客户端 Adapter | 交互结果，INFO | 每次尝试 1 条 | `event.outcome`、`duration_ms`、目标与协议字段 |
| 自有依赖失败，且恢复 / 最终记录无法指认具体调用 | 依赖 Adapter | 调用结果，INFO | 每次失败尝试最多 1 条 | `event.outcome=failure`、`duration_ms`、依赖字段 |
| 业务事实或状态变更已经成立 | 业务代码 | 业务事实，INFO | 每个事实 1 条 | `event.action`、业务键 |
| 预期业务规则拒绝已最终确定，且无需系统恢复 | 业务拒绝处理点 | 业务结果，INFO | 每个拒绝结果最多 1 条 | `error.code`；已有业务键时带上 |
| 已执行降级 / 兜底，或已确定还会重试 | 恢复点 / 重试 Adapter | 恢复事件，WARN | 每次恢复决定 1 条 | 恢复事实；重试时带 `retry.*` |
| 失败无人接盘且需要人工介入 | 执行单元最终处理点 | 最终错误，ERROR | 每个最终失败最多 1 条 | `error.code`；有异常对象时带 cause |
| 长任务或长连接开始，且场景明确要求开始事件 | 场景基础设施 | 生命周期事件，INFO | 场景规定 | 场景标识 |
| 已启用自有依赖慢调用检测且超过阈值 | 依赖监测 Adapter | 慢调用，WARN | 每次超阈值调用 1 条 | `duration_ms`、依赖字段 |
| 仅用于过程排查 | 业务代码或 Adapter | DEBUG | 按需 | 定位所需字段 |

“实际尝试”指 Adapter 能独立观察并计时的一次调用。重试框架暴露每次尝试时逐次记录；底层传输重发不可观察时不编造日志，只在可观察的最终结果保留 `retry.*`。

业务代码通常只记录业务事实、实际恢复和 DEBUG；不得自行记录 canonical、客户端交互结果，也不得创建或传播 trace。

级别只使用 `ERROR / WARN / INFO / DEBUG`，对应 OTel SeverityNumber `17 / 13 / 9 / 5`。ERROR 表示需要人工介入并进入告警评估；告警系统可以聚合、过滤和抑制。无人需要处理的失败不得记为 ERROR。DEBUG 在生产默认关闭，只按模块临时开启。

canonical 与交互结果即使失败也保持 INFO，结果写入 `event.outcome`；恢复 WARN、最终 ERROR 是不同记录，不能互相替代。唯一例外是场景明确把异步结果与最终处理合并，此时失败记录为 ERROR。业务代码禁止为过程或进度在循环中逐项记录 INFO 及以上；批量处理中已经分别成立的独立业务事实仍按每个事实一条 INFO。场景基础设施规定的 canonical 或生命周期事件不受此条影响，采样与排除见 [ROADMAP.md](ROADMAP.md)。

## 2. 字段契约

所有环境每行输出一个 ECS JSON 事件，dev 与 prod 使用相同 schema。ECS 已定义的字段使用 ECS 名；关联使用 `trace_id` / `span_id`；平台字段使用 snake_case。禁止创建同义别名，例如 `requestId`、`correlation_id`、`traceId`、`elapsed`、`retry_times`、`outcome`。

可选事实不存在或无法可靠取得时，**省略字段**；禁止用 `null`、空串或假 `0` 表示未知。同一字段在不同语言与场景中必须保持相同 JSON 类型。

### 2.1 每条日志由框架生成

`@timestamp`（UTC）、`log.level`、`log.logger`、`message`、`process.*`、`service.name`、`service.version`、`service.environment`、`ecs.version`。

### 2.2 公共字段

| 字段 | JSON 类型 / 约束 | 出现条件与含义 | 写入方 |
|---|---|---|---|
| `trace_id` | string，32 位非全零 hex | 执行单元内所有应用日志；业务流查询键 | 上下文 Adapter / OTel |
| `span_id` | string，16 位非全零 hex | 执行单元内所有应用日志；只用于与 Trace 对位 | 上下文 Adapter / OTel |
| `tenant_id` / `user_id` / `device_id` / `client_id` | 类型由领域统一登记 | 入口可信解析到对应身份时出现；日志只投影，不另建来源 | 认证 / 租户 / 设备网关组件 |
| `event.action` | string，`<域>_<过去式动作>` | 每条业务事实 INFO 必有；仓库内稳定枚举 | 业务代码 |
| `event.outcome` | string：`success / failure / unknown` | canonical、业务边界交互结果与需留痕的自有依赖调用结果必有；只表示本次结果 | 场景 Adapter |
| `duration_ms` | number，`>= 0` | 所有计时结果事件必有；墙钟毫秒，不用时间戳相减 | 场景 Adapter |
| `retry.attempt` | integer，`>= 1` | 当前失败尝试、再次处理或最终重试结果；最终成功也保留 | 重试 Adapter |
| `retry.max_attempts` | integer，`>= retry.attempt` | 已配置重试上限且出现 `retry.attempt` 时出现 | 重试 Adapter |
| `disposition` | string：`retry / dead_letter` | MQ 消费失败且已决定后续处置；成功省略 | MQ 消费 Adapter |
| `error.code` | string，稳定枚举 | 最终 ERROR 必有；其它日志仅在已有明确语义分类时出现 | 异常对象 / 最终处理点 |
| `error.type` | string | 交互失败或最终 ERROR 有异常对象时可以出现 | 日志框架 |
| `error.message` / `error.stack_trace` | string | 仅带异常对象的最终 ERROR 出现；WARN / INFO 不带堆栈 | 日志框架 |
| `http.request.body.bytes` / `http.response.body.bytes` | integer，`>= 0` | HTTP 入站或出站且长度已知；未知、流式或大文件省略 | HTTP Adapter |
| `messaging.message.body.size` | integer，`>= 0` | MQ 生产或消费时记录消息正文实际字节数 | MQ Adapter |

HTTP 2xx / 3xx 为 `success`，4xx / 5xx 为 `failure`；无状态码或 1xx 为 `unknown`。gRPC OK 为 `success`，其它状态为 `failure`。`event.outcome` 不承载 retry / dead-letter 状态。

`event.action` 和 `error.code` 是聚合键，`message` 不是。`event.action` 只表示已经成立的业务事实；预期业务拒绝是业务结果，使用 `BIZ_` 类 `error.code`，不为通用异常处理器编造 `event.action`。业务事实不等同于 MQ 集成事件；合规审计使用独立通道。具体场景字段由[场景文件](#8-场景)补充。

## 3. trace 与身份上下文

- 业务代码不得创建、传播或改写 trace / span。
- 进入执行单元后，应用日志必须带 `trace_id` 和 `span_id`；启动、框架和库日志允许缺失。
- 整条业务流查询 `trace_id`；查看某个服务时查询 `trace_id + service.name`。`span_id` 不作为日志执行单元 ID。
- 入口能可信解析到什么身份就注入什么，并在执行单元内自动携带；日志只投影已注入的身份，业务代码不重复传递。

OTel SDK 接入前：

- 必须使用兼容 W3C Trace Context 的组件；禁止用正则或业务代码手写 `traceparent` 解析。
- 合法上游上下文继承 `trace_id`、parent / flags 及 `tracestate`，并为当前单元生成 `span_id`。
- 缺失或非法上下文（包括全零 trace-id / parent-id）必须丢弃并重建 `trace_id`、`span_id`。
- 出站通过同一组件注入 `traceparent`，存在 `tracestate` 时一并传播。新的 MQ 消费或异步任务执行单元只携带 `trace_id` 时，下游校验后沿用并生成新的 `span_id`；同一执行单元内的纯线程切换传播完整当前上下文，不新建 span。

OTel SDK 接管后，trace 的生成、传播、子 span 与出站注入均由 SDK 负责，自管 trace 实现退役；既有身份字段注入与 canonical 保留。日志字段仍为 `trace_id` / `span_id`，必要时在 Adapter 中映射 SDK 默认 key，不修改 trace 拓扑。

HTTP 响应回写 `X-Trace-Id`，不向外部调用方回写完整 `traceparent`。公网入口可以配置为不采信入站 `traceparent`。不可控链路使用领域键桥接，见 [TRACE-BREAK.md](decisions/TRACE-BREAK.md)。

## 4. 执行单元与调用分类

执行单元统一遵守：

1. 入口注入 trace 与身份上下文，退出时只清理本层写入的上下文。
2. 出口记录一条 canonical INFO；结果与耗时使用字段表达，不随成功失败升级级别。
3. 未恢复异常只在本单元最终处理点记录一次 ERROR。canonical 与最终 ERROR 是两条记录。

canonical 与交互结果的 logger 统一使用 `<域>.<动作>`，具体名称由场景文件规定；canonical 依靠稳定 logger 聚合。

| 分类 | 示例 | 规则 |
|---|---|---|
| 入站执行单元 | HTTP、gRPC、MQ 消费、Job、异步任务、设备或长连接会话 | 出口一条 canonical INFO |
| 业务边界交互 | 出站 HTTP / RPC、跨域 MQ、大模型、跨账号对象存储 | 每次可观察的实际尝试一条交互结果 INFO |
| 自有依赖调用 | Redis、MySQL、PG、Mongo、ES、本地文件 | 成功默认不记 INFO |

分类依据是业务关系，不是部署位置、频率或协议。构成业务边界时，成功和失败都记录交互结果 INFO；不构成业务边界时，成功使用 DEBUG 或不记，失败不能只记 DEBUG：恢复 / 最终记录能够指认调用时直接按实际处置记录 WARN / ERROR，否则由 Adapter 先留一条结果 INFO。

自有依赖慢调用检测是可选项：超过配置阈值时记录 WARN，使用 `db.system`、`db.operation.name`、`db.namespace`、`duration_ms`；逐次排查使用 DEBUG。禁止默认记录 `db.query.text`。对应级别关闭时，必须在序列化前停止组装 message、SQL 或参数。用户可感知的文件写入结果属于业务事实，使用 `event.action`。

### 4.1 业务边界失败三层

一次失败最多涉及三种职责不同的记录：

1. **交互结果**：客户端 Adapter 对每次可观察的实际尝试记录 INFO，带 `event.outcome=failure`、`duration_ms`；有异常对象时可以带 `error.type`。它不决定是否需要告警。
2. **恢复事件**：只有已执行降级 / 兜底或确定还会重试时，恢复点 / 重试 Adapter 才记录 WARN。确定耗尽的当次不再记录恢复 WARN。
3. **最终错误**：失败无人接盘时，由执行单元最终处理点记录一次 ERROR。`event.outcome`、`duration_ms`、`retry.*` 保留在交互结果或 canonical，不复制到 ERROR；场景明确允许结果与最终处理合并时除外。

出站必须传播 trace 上下文。异步发送的执行单元已经结束时，完成 callback 是该次发送的最终处理点；若它同时承担交互结果，失败记录合并为一条 ERROR，并保留结果、重试字段及必有的 `error.code`。具体规则见 [MQ-PRODUCE.md](scenes/MQ-PRODUCE.md)。

## 5. 异常与重试

| 类别 | 判定 | 级别 | `error.code` 前缀 |
|---|---|---|---|
| 业务失败 | 预期规则拒绝、无需系统恢复 | INFO；若实际执行恢复则另记 WARN | `BIZ_` |
| 依赖失败 | 外部系统超时、不可用或返回错误 | 交互结果 INFO；恢复 WARN；耗尽且无人接盘、需要人工介入时 ERROR | `DEP_` |
| 系统缺陷 | bug、未预期异常、不可达分支 | ERROR | `SYS_` |
| 资源环境 | OOM、磁盘满、连接池耗尽、配置错误 | ERROR | `ENV_` |

`error.code` 格式为 `<类别前缀><域>_<语义>`，全大写蛇形，例如 `DEP_STOCK_SERVICE_TIMEOUT`。它必须稳定、可枚举、语言无关；本仓库统一登记，跨服务治理见 [ROADMAP.md](ROADMAP.md)。告警和聚合使用 `error.code`，不使用异常类名或错误字符串。

- 异常只在执行单元最终处理点记录一次；禁止记录后继续抛出。
- 中间层只通过 wrap-and-rethrow 补充当时的业务键、数量等上下文，并保留 cause 链；语义更精确时赋新 `error.code`，否则继承内层码。
- 最终处理点取最外层已携带的码；链上无码或没有异常对象时按分类赋码，缺省 `SYS_UNEXPECTED`。
- 没有恢复策略的代码不做宽 catch；禁止以“宽 catch + WARN”吞掉异常。
- WARN / INFO 不记录堆栈；无异常对象的 ERROR 不编造异常字段。

### 5.1 重试

| 状态 | 记录 | 字段 |
|---|---|---|
| 确定还会再试 | 恢复点 / 监听器记录 WARN | 当前 `retry.*`；被计时则带 `duration_ms` |
| 确定耗尽 | 不再记录恢复 WARN；仅当无人接盘且需要人工介入时由最终处理点记录 ERROR | 结果与重试字段留在交互结果或 canonical |
| 最终成功且中间失败过 | 最终交互结果或 canonical 保持 INFO | 必须带最终 `retry.*` |

重试次数从框架读取，同一路径只能选择一个计数器。单次成功且从未重试时省略 `retry.*`。同步、异步 callback 与 MQ 消费的最终处理方式由对应场景补充。

## 6. 输出、消息与安全

- 所有环境输出同一套单行 ECS JSON。默认输出到 stdout 并由运行时采集，应用不写运行日志文件，也不负责滚动；仅当运行环境无法从 stdout 采集时，按 [6.1 文件输出](#61-可选文件输出) 启用可选文件模式。
- 日志、Metrics、Trace 各自独立，通过 `service.name`、`trace_id` 关联；业务代码不得维护与 OTel 平行的 span 树，也不得把 Metrics 仪器名整表搬入日志。
- 源码中的 `message` 必须是单行字面量模板，使用参数化占位符，禁止字符串拼接。需要检索、过滤或聚合的值必须同时写入结构化字段。
- 描述性文字使用中文；业务术语、属性名和字段值保留原值。
- 禁止直接使用标准输出、裸打堆栈、记录后再抛出，具体语言写法见[语言绑定](#9-语言绑定)。

运行日志禁止明文记录：

- 密码、token、API key、session 凭证；
- 身份证、银行卡、完整手机号等敏感个人信息；
- `url.query`、带 query 的完整 URL、原始 header；
- 请求 / 响应正文、MQ 消息正文、SQL 全文、模型输入输出；
- 失败载荷全文或其它未列入白名单的内容。

确有排查需要时只记录白名单字段；完整手机号等按业务要求脱敏。已知的载荷字节数不是明文，可以记录。禁止在 `gen_ai.*` 或其它 OTel 语义前缀下自造非官方属性名，具体字段见 [GENAI.md](scenes/GENAI.md)。

### 6.1 可选：文件输出

默认且优先 stdout 采集。仅当运行环境无法从 stdout 采集（例如既无容器运行时、又不由 systemd / journald 托管的裸机部署）时，才可以启用文件输出。启用后事件内容、字段契约与级别规则不变，仍是单行 ECS JSON；文件由日志框架写入并滚动。以下规则整套强制，不允许按服务自定义：

| 项 | 规则 |
|---|---|
| 目录 | 部署根目录下的 `logs/`，随部署单元存放；部署可用环境变量覆盖为其它路径，同一环境内必须一致。目录由部署创建并授权 |
| 日志流 | 固定三个流：`debug`（仅 DEBUG）、`info`（INFO 及以上，主流）、`error`（仅 ERROR）；除此之外禁止按模块、场景或其它级别组合拆分文件 |
| 当前文件名 | `<service.name>-<流>.log`，例如 `lingmai-sample-info.log`；每个流每实例只有一个当前文件 |
| 滚动文件名 | `<service.name>-<流>-<yyyy-MM-dd>.<i>.log.gz`，`<i>` 为同日内从 0 递增的序号 |
| 滚动方式 | 按天滚动，叠加单文件上限 200 MB；写满即滚动，不等日界 |
| 压缩 | 滚动时 gzip；当前文件不压缩 |
| 保留 | `info` / `error` 流各最多 7 天且总量 2 GB；`debug` 流最多 3 天且总量 1 GB。任一先到即删除该流最旧滚动文件；文件只是采集缓冲，不是长期存储 |
| 多实例 | 同一主机运行同一服务多实例时，目录或文件名必须含实例标识，禁止两个进程写同一当前文件 |

- ERROR 事件同时进入 `info` 与 `error` 流；采集器只 tail `info` 流并按行解析 JSON，`error` / `debug` 流只用于人工排查和补采，禁止多流同时入库造成重复。
- `debug` 流只在按模块临时开启 DEBUG 时产生内容；生产默认关闭 DEBUG 的规则不变。
- 滚动、压缩与清理由日志框架完成；禁止外部 logrotate 截断或移动当前文件（copytruncate 会丢行）。
- 启用文件输出不关闭 stdout，stdout 与 `debug + info` 两流内容之和一致；采集只认 stdout 或文件其中一个入口。

## 7. 标准排查路径

结构化日志依赖集中检索系统发挥完整价值；单实例阶段可以 `grep <trace_id>`。

1. 有 `X-Trace-Id` 时直接取得 `trace_id`；只有症状时先按身份字段或 canonical 的状态、耗时定位。
2. 用 `trace_id` 查看整条业务流；增加 `service.name` 查看单个服务。
3. 使用交互结果判断问题发生在哪一侧（通常为 INFO；异步最终失败合并时为 ERROR）；使用 `error.code` 分类，使用 cause 链定位根因和中间层上下文。

## 8. 场景

| 文件 | 适用范围 |
|---|---|
| [INBOUND-REQUEST.md](scenes/INBOUND-REQUEST.md) | HTTP / gRPC unary 入站 |
| [MQ-CONSUME.md](scenes/MQ-CONSUME.md) | MQ / 事件消费 |
| [JOB.md](scenes/JOB.md) | 定时任务 |
| [ASYNC.md](scenes/ASYNC.md) | 线程池 / 协程 / 任务队列 |
| [DEVICE.md](scenes/DEVICE.md) | 设备会话与指令 |
| [OUTBOUND-CALL.md](scenes/OUTBOUND-CALL.md) | 出站 HTTP / RPC / 跨账号对象存储 |
| [MQ-PRODUCE.md](scenes/MQ-PRODUCE.md) | MQ 生产 |
| [GENAI.md](scenes/GENAI.md) | 大模型客户端 |
| [SESSION.md](scenes/SESSION.md) | WebSocket / SSE / gRPC 双向流 |

## 9. 语言绑定

| 文件 | 技术栈 |
|---|---|
| [JAVA-SPRING-BOOT.md](bindings/JAVA-SPRING-BOOT.md) | Java / Spring Boot、SLF4J、Logback、MDC |

## 10. 非活规则

- [NO-CUSTOM-CORRELATION.md](decisions/NO-CUSTOM-CORRELATION.md)：不引入 `correlation_id` / `request_id` 的理由。
- [TRACE-BREAK.md](decisions/TRACE-BREAK.md)：不可控链路 trace 断裂的已知局限。
- [ADOPTION.md](ADOPTION.md)：本仓库落地进度。
- [ROADMAP.md](ROADMAP.md)：不在当前版本范围的演进。
