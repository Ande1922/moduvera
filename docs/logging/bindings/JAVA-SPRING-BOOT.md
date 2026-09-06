# 日志规范：Moduvera Java / Spring Boot 绑定

本文将 [LOGGING.md](../LOGGING.md) 映射到本仓库的 Java 接入边界，不能改变主规范语义。规范与接入设计已确认，基础设施接入待实施；状态与证据责任见 [ADOPTION.md](../ADOPTION.md)。

2026-09-06，维护者整体确认[接入方案](../INTEGRATION-DESIGN.md)。本绑定已同步字段、生命周期与运行装配选择，以下要求是实现目标，不表示组件和验证已经存在。

灵脉的[源 Java 绑定](../references/LINGMAI-JAVA-SPRING-BOOT.md)保留为实现参考。其中的 `tech.sunseed.lingmai.*`、`LINGMAI_LOG_DIR`、Formatter、Filter、拦截器和 Starter 自动配置属于源项目，不能直接作为本仓库运行配置或已实现能力。

## 1. 业务日志写法

沿用 SLF4J 门面、Logback 实现、结构化 key-value 和参数化消息；不增加通用日志二次门面。业务代码不直接依赖 Logback、不使用 `System.out`，也不手动写 MDC 或生成、传播 Trace。

只有主规范打印决策允许的角色才记录日志。业务事实由业务责任方记录，canonical 与客户端交互结果由基础设施记录；中间层 catch-wrap-rethrow 保留 cause，不重复记录最终 ERROR。

下例展示已经成立的业务事实的调用形状；它不说明数据库 `save` 返回即可证明事务已经提交：

```java
log.atInfo()
        .addKeyValue("event.action", "order_created")
        .addKeyValue("order_id", orderId)
        .setMessage("订单创建成功, orderId={}")
        .log(orderId);
```

最终处理点的 ERROR 带稳定 `error.code`，有异常时通过 fluent API 设置 cause。此写法的输出仍须经下节的 Formatter 验证；仅有调用代码不证明 JSON 字段已经合规。

```java
log.atError()
        .addKeyValue("error.code", errorCode)
        .setCause(failure)
        .log("操作最终失败");
```

主规范的敏感信息禁令同样约束异常 message、完整 cause 链和结构化字段；不得认为使用 `setCause` 就自动完成脱敏。

## 2. 输出配置的实施边界

所有环境沿用同一 ECS JSON schema，级别与环境标识按主规范配置。可选文件输出的适用环境、分流、滚动与保留规则继续遵循主规范；灵脉的 XML 仅作为实现参考。

本仓库当前 Parent/BOM 使用 Spring Boot 4.1.1。源绑定关于原生 ECS 同时处理 `error.code` 与 throwable 的故障说明需要在实际采用版本上验证，不能仅凭源文档决定新增 Formatter 或覆盖 Boot 默认配置。

实施者在选定本地 Formatter 与配置前，至少取得以下输出证据：

- `error.code`、`error.type`、`error.message`、`error.stack_trace` 共存时整行仍可解析，字段不丢失、不出现重复 JSON 键。
- MDC 与 fluent key-value 的字段名、JSON 类型、未知值省略行为符合主规范；`trace_id` / `span_id` 的映射不改写实际 Trace 拓扑。
- 最终异常的 cause 链和常见 HTTP/数据库错误不会输出被禁止的正文、query、凭据或 SQL。
- 开发与生产使用相同 schema；文件输出若启用，仍遵守单行 JSON、实例隔离和容量边界。

默认采用当前 Boot 原生 ECS；仅当上述输出证据显示冲突时补最小 Formatter 修正。本次未运行这些测试，也未编写 Formatter。

## 3. 上下文与入口

MDC 是日志投影载体，可信业务身份由既有入口与 ExecutionContext 建立。日志不能从未经认证的请求头或 Trace Baggage 建立 Tenant / Actor，也不能把权限集合或整个上下文序列化到日志。

- 首版直接使用 OpenTelemetry Java Agent，执行主规范的 SDK 接管路径。`trace_id` / `span_id` 取自实际 OTel 上下文，业务代码不创建或修改；不移植接入 SDK 前的自管 Trace 实现，不另行初始化第二套 SDK。Agent 未覆盖的项目边界通过同一 OTel API / 上下文补充，实际覆盖需验证。
- 同一执行单元的线程切换与新异步执行单元按主规范区分。传播需保持隔离、缺失值和执行结束后的恢复语义；不把源 `MdcTaskDecorator` 当作本项目已存在的传播组件。
- `correlation_id`、`tenant_id`、`actor_type` / `actor_id`、`initiator_type` / `initiator_id` 均按主规范投影 string。只有当前 Actor 为 USER 时填 `user_id`；未知字段省略，不从 Baggage 或原始 Header 推断身份。
- 根入口生成独立 UUID v4 correlation，下游继承，ExecutionContext 与持久消息承载；不改用 commandId，也不能把原字段改名为 `trace_id` 或强制截断、补零。普通业务表不统一加列。ExecutionContext 保留既有合法性和非空约束，Kernel 不引入 OTel 依赖。
- 公网入口忽略调用方 correlation；内部 HTTP 接受 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`，缺失/非法时只补建一次并记录恢复 WARN。内部来源必须由受保护的装配/入口边界确定；缺失身份仍按原安全契约拒绝。旧消息读取继续使用既有模型约束，不把 HTTP Header 正则额外施加到旧信封 schema；原本不能进入 ExecutionContext 的历史值仍按既有契约失败，不改写原 C。
- Servlet request attribute 与 Gateway exchange / Reactor Context 承载一份请求诊断状态：correlation、单调计时起点、server Span Context、可信身份字段快照及一次性结束标记。认证前不创建伪造业务身份的 ExecutionContext；认证后保存仅供日志的不可变身份快照，实际授权作用域仍及时结束。
- Servlet、Gateway 的 Reactor 链路、消息消费与任务入口需要各自的生命周期证据。一个 HTTP Filter 的通过不能代表其他入口已经符合规范。

## 4. HTTP、消息与重试映射

HTTP 载荷字节沿用源绑定的原则：已知 `Content-Length` 或已有字节计数才记录，不读取或缓存正文来打印日志；未知值省略。流式响应、SSE 与长连接按场景文档处理。

HTTP 无状态码但已观察到传输失败时统一为 `event.outcome=failure`，仅无法得知结果时为 unknown；`duration_ms` 用单调时钟差值计算。调用失败不证明远端业务未执行。

正常响应回写 `X-Correlation-Id`、`X-Trace-Id`，Problem 保留 `correlationId` 并使用同一请求状态，不新增 Trace 正文字段。Gateway 自产错误同样覆盖认证前拒绝与未匹配路由；下游 Problem 正文透明转发。下游关联异常不一致时保留入口 Header 与原正文并记录契约失败，不通过重写正文掩盖断点。响应已提交或客户端断开时不伪造已发送 Header/状态码。

Servlet 异步请求在真实 complete/error/timeout 时收口；Reactor 在实际终止回调收口，竞争回调只记一次 canonical。最终日志使用本执行单元捕获的 Trace 与可信身份快照。线程回调只短暂安装 Scope/MDC，`finally` 恢复进入前状态；禁止跨异步等待持有 ThreadLocal Scope 或用 `MDC.clear()` 清除上层状态。

消息尝试、重试和死信字段来自实际控制该次行为的设施，不编造底层重发次数。按照 [ADR 0030](../../adr/0030-make-publication-guarantees-explicit.md)，`DurablePublication.append` 返回只证明发布意图加入本地事务，不能记录为 Broker 发送成功；Relay / Transport 的结果对应实际发送尝试。日志不改变可靠发布、消费事务或重试归属。

`MessageId` 是独立消息身份，不能被 Trace ID 或业务关联 ID 替代；`causationId` 保留直接消息因果语义。Outbox 追加时捕获不可变 creation Context，初始 publication Context 引用它。人工 redrive 的新根 Trace Link creation，publication Context、代次递增与原有 TERMINAL → PENDING 的 CAS 状态变更原子保存；自动发送、重试、租约接管和重启恢复使用当前代次。MessageId/correlation/失败计数与 Inbox 去重键保持原契约。历史 Trace 缺失或损坏按整体方案第 5.3 节持久修复，不能伪造历史关联。

creation 放入信封纯值，Kafka transport header 由 Agent 表示本次投递上下文。消费者保留 Agent 当前有效 Context，creation 仅作 Link；缺少传入 parent 不代表需要再建一个根。Outbox append/publish 和消费业务尝试的补充 INTERNAL Span 不复制 Agent producer/consumer Span；实际数量、parent/Links 与 header 必须用锁定版本验证。

## 5. 与现有契约共存

源绑定中的 `BizException`、库存缓存降级和 `ApiError` 是日志示例，不定义 Moduvera 业务实现或公共 HTTP 形状。当前错误码、授权与租户规则不随日志采用改变；HTTP 继续遵循 [ADR 0011](../../adr/0011-use-native-http-semantics-and-service-owned-api-versions.md) 的真实状态码和 RFC 9457 Problem Details。

## 6. 源场景的本地适用条件

九份源场景保留导入原文；其公共语义服从已修订主规范，下列适用条件防止把源项目的接入前或未支持场景当作本仓库实现：

- **ASYNC：** 普通线程切换传播完整 Context；进程内新任务提交前捕获、实际执行时创建子 Span；显式持久任务保存 C 与触发 Context，实际执行新 Trace 并 Link。未开始执行就取消/提交失败不记录虚假的任务完成，排队与执行耗时分开。
- **MQ：** “接入前读取 trace_id”由 Agent 的标准传播取代；信封 creation 与本次 transport header 不混用。底层 callback 只有在无人承接失败的异步发送中才是最终处理点；当前同步 ACK 的 Outbox 由 Worker 决定重试/terminal，底层不能抢先记录最终 ERROR。发送日志是否需要 INFO 仍按主规范业务边界分类，不因新增 Trace 而让全部内部发送打印 INFO。
- **JOB：** 独立定时根触发新 C/Trace；已有关联触发的持久任务继承 C、新 Trace + Link；原消息恢复使用已持久的新发布代次。Relay poll 和租约接管不构成新的业务根操作。源 Job 生命周期规则不代表本轮新增调度平台。
- **HTTP 与其他场景：** HTTP 补充双 Header、真实完成与身份快照；设备、GenAI、长连接等按 Product Surface 的支持范围处理，导入场景不自动启用运行能力。

## 7. 装载、输出与验收责任

框架提供公共日志 Starter 和各运输/执行边界的适配，业务继续使用 SLF4J，不引入通用 logger 二次门面。公共组件不强制依赖 Servlet、Reactor 与 Kafka 的全部组合。

Agent 使用实施验证后锁定版本与 SHA-256 的外置制品，开发/验收脚本准备、容器只读挂载，公共镜像不为本轮内置；运行装配注入参数。受治理启动缺失 Agent 或装载错误提前失败，库级测试可不装载。采集后端失联则有界异步处理，不改变业务事务或 Broker ACK。

本地/验收配置：`otel.propagators=tracecontext`；Trace 走 OTLP `http/protobuf` 到验证接收端；`otel.logs.exporter=none`，ECS JSON 走 stdout；`otel.metrics.exporter=none`，应用指标继续使用 Micrometer。验收根 Trace 全采样并单测未采样传播；正式采样比例、后端和保留期归环境配置。

PostgreSQL/MySQL 追加迁移，保留 V1 与历史数据，正常启动仍执行校验策略。消息新增可选 Trace 扩展需先更新消费者和 schema，旧严格 schema 不能读取新字段的边界必须验证；不自动更新所有 MessageType major。完整验收矩阵见[整体方案第 10 节](../INTEGRATION-DESIGN.md#10-实施完成需要的证据)，当前未取得运行通过证据。
