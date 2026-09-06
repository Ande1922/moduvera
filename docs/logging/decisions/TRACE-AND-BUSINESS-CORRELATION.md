# 决策：区分技术追踪与业务关联

状态：2026-09-06 维护者已整体确认[接入方案](../INTEGRATION-DESIGN.md)，包含独立 Correlation ID、Trace 持久化、生命周期、HTTP 与迁移边界；运行实现和验收尚未完成。日志活规则见 [LOGGING.md 第 3 节](../LOGGING.md#3-trace-与身份上下文)。

## 决策经过

1. 维护者同意采用灵脉日志底稿、首版直接使用 OTel Java Agent，并说明当前只有脚手架，没有实际业务消费者。
2. 随后同意统一关联方向，但要求先看影响范围；最初范围稿据此提出全面退出旧 correlation 机制。
3. 维护者在范围审阅时指出：此前把 Trace ID 与 Correlation ID 视为等价，可能遗漏持久化重放场景，要求先判断语义是否成立。
4. 经区分执行追踪、业务会话、消息身份与直接因果关系后，维护者回复“我认同你的这个判断”，确认下述修订。没有外部兼容负担不能证明语义等价；最初的全面删除方案由本决定替代。
5. 曾对“首版按库存预占消息会话复用 commandId，暂不引入更大的流程级关联”回复“认可”。这是当时确认的局部最小方案，随后由第 6 项替代，保留本条仅用于追溯。
6. 维护者进一步比较根入口独立生成 correlationId 的存储成本与语义整洁性。核对后确认：现有执行上下文、消息信封与 Outbox 已有 correlation 载体，复用 commandId 的值不会在通用承载方案中省掉字段。对“根入口独立生成、下游继承、执行上下文与持久消息承载、业务表不统一加列”的推荐回复“那就先定这个方案吧”，确认下述现行选择。
7. 维护者要求剩余时机直接拟定一套完整方案，供整体审阅，不再逐项对齐。当时形成[整体接入稿](../INTEGRATION-DESIGN.md)，该步仅授权整理方案，尚不表示确认新增细节。
8. 维护者进一步核对“Outbox 的 Trace 也要持久化”及“全局 correlation 是否减少改动范围”，确认保留现有 correlation 能减少废弃字段的连锁迁移，但不能替代 Trace 接入；随后回复“好的，那就这样定”，整体确认接入方案。此后同步正式日志规范与 Java 绑定，不推断运行实现已完成，也不新建 Spec/票或推进执行状态。
9. 后续完整性检查发现既有可观测性 Spec 仍是 needs-info 占位。维护者再次调用 to-spec 后，按已确认设计补全同一规格，并同步 HTTP 既有 Spec/票及 ExecutionContext 共享接缝；正式规格 needs-triage 待审阅，未新建实施票或实施运行代码。

## 已确认的选择

- 技术诊断统一使用 `trace_id` / `span_id`，由 OTel 管理；不另造诊断 ID 或第二套 Trace 引擎。
- 保留跨 Trace 的关联能力。Correlation ID 标识一次根入口操作及其因果派生工作；不作为 Trace ID、Command ID 或 Message ID 的别名，不要求与它们值相等或同步更换。
- 新根入口由基础设施生成独立 correlationId，内部 HTTP、异步任务与消息消费继承已有值；同一入口派生多个命令时共享关联，各命令保留自己的身份。它不自动代表整个订单生命周期，也不自动合并独立的客户端提交。
- 消息身份、直接因果关系和业务关联各有职责。重投原消息保持 MessageId；仍属于原操作时保持已有业务关联。新的业务命令需与原消息重投区分。
- Outbox 的 Trace Context 持久化与恢复是独立责任，不能因保留 Correlation ID 而省略。正常 Outbox 首发、自动重试与宕机续投延续当前发布代次 Trace；人工重投建立新 Trace 并 Link 原创建上下文，新代次与重投状态原子持久化。消费者保留本次有效投递上下文，不用旧 creation 覆盖当前 parent。
- 业务身份与 Trace 分开，Kernel 保持 framework-free。业务关联字段也不参与认证或授权。

## 已确认的生命周期与承载

- 根入口先建立 correlationId，因此商品读取与校验阶段即可以关联。普通查询作为新的根入口也有自身关联，不再要求等待业务命令出现；内部请求沿用上游值，不在每个服务重复生成。
- 原消息的重试、补发、人工重投恢复消息中保存的 correlationId；重投管理请求自己的新关联不能覆盖原消息。新的根操作生成新的关联。
- 客户端再次提交默认是新的入口与关联。若业务要求认定为原操作，必须由明确的幂等或流程记录恢复关联，不能仅凭相同实体 ID 或任意请求头推断。
- ExecutionContext 承载关联元数据并复用快照与作用域机制；它不参与 Tenant / Actor / Initiator 的认证授权判断。Trace 由 OTel 独立管理，普通业务方法不为技术传播增加上下文参数或手写 MDC。
- 消息信封与 Outbox 持久化关联；当前已有对应字段，不因采用独立 ID 而要求给全部业务表加列。Inbox 的去重身份保持不变。
- 持久任务或流程若需要在未来恢复原操作，保存相应关联元数据。若只能从实体表重新构造工作，而原消息/操作记录已不存在，就不能凭空恢复旧关联。
- Reference Product 保留原库存预占 commandId、结果 MessageId 和 causationId；不再将 commandId 的值设置为通用 correlationId。

本决定确认设计目标。当前代码已有 UUID / Header 与持久消息传播机制，但根入口信任、缺失值、重放及 OTel 接入尚未按此契约完整验证，不能据此声称已实现。

## 未采用的替代方案

| 方案 | 不采用的原因 |
|---|---|
| 全面用 Trace ID 替代业务关联 | 将业务操作边界绑定到 Trace 拓扑；新建 Trace 后不能再依靠单个 Trace ID 查询整个操作。 |
| 每个入口都生成 Trace ID 与 Correlation ID，要求二者等价 | 增加两个同义标识，没有获得独立的业务语义。 |
| 首版以 commandId 兼作通用业务关联 | 曾作为局部最小方案确认；在统一上下文与消息载体下不省字段，且关联开始较晚、框架适配需了解具体命令，现由独立 correlationId 替代。 |
| 原样保留现有 correlation 实现即视为完成 | HTTP Header / UUID 的传递机制没有自行定义业务会话的创建、继承与结束边界。 |

## 已确认的整体默认值与实施证据

入口、字段、HTTP、身份、重试/重投、发布代次、清理、装载与迁移策略已整体确认，统一见[整体接入方案](../INTEGRATION-DESIGN.md)。主规范与 Java 绑定已同步对应规则：公网生成 correlation、内部继承，HTTP 保留 correlation Header/Problem 并回写 X-Trace-Id；业务上下文保持非空且不依赖 OTel；Actor/Initiator 分别投影；请求真实结束后一次记录并恢复作用域；追加数据库迁移，Agent 外置装载，stdout/OTLP/Micrometer 分工。审计能力保持独立专题。

实施者仍需取得锁定 Agent 的 parent/Link 与 Span 数量证据、真实数据库原子更新与重启恢复证据，以及 HTTP/线程清理和 JSON 输出证据。历史未知的 Trace 不伪造，审计及生产观测后端按专题边界明确留后。

设计决定与规范同步后，已依后续 to-spec 请求整理[正式规格](../../../.scratch/governed-observability/spec.md)及相邻契约；未开新票或实施，规格仍待审阅。协调清单见整体方案第 9 节，现有引用盘点见 [TRACE-CONVERGENCE-SCOPE.md](../TRACE-CONVERGENCE-SCOPE.md)。

## 核对依据

- [W3C Trace Context](https://www.w3.org/TR/trace-context/#trace-id) 定义 Trace ID 的追踪身份。
- [OTel Tracing API](https://opentelemetry.io/docs/specs/otel/trace/api/#end) 允许已结束 Span 继续作为父节点；HTTP 结束不代表异步处理必须换 Trace。
- [OTel 消息语义约定](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/#trace-structure) 支持通过 Span Link 关联消息处理，并单列 conversation ID；相关消息约定仍处于 Development，不能据此声称所选 Agent 已具备全部行为。
