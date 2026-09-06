# 技术追踪与业务关联的接入范围

2026-09-06 已确认接入范围。维护者已确认采用灵脉日志底稿、首版直接接入 OTel Java Agent、当前没有实际业务消费者，并在持久化重放讨论后确认：技术追踪与业务关联职责分开，后者由根入口独立生成、下游继承，并由执行上下文与持久消息承载。原“全面退出 correlation”方案已被替代，见[决策记录](decisions/TRACE-AND-BUSINESS-CORRELATION.md)。本稿保留源码影响证据并记录已确认范围，不是实施完成报告或新 tracker spec。

检查基线：`ce1636f82f9652b2b14dd9e146e62c748fbf3233` 的当前工作树，包含未提交的日志文档与其他专题。本文区分当前代码、已确认方向和待定实现；本轮未修改 Java、POM、SQL、消息 Schema、启动脚本或测试。

入口、时机、装载与迁移策略已按[整体接入方案](INTEGRATION-DESIGN.md)获维护者确认。本文保留前轮源码盘点，区分已选定设计与仍待取得的实现证据。

## 1. 范围判断与数量

这是横跨入口、消息和持久化的基础设施契约调整。业务规则变更不在目标内；主要复杂度来自 Outbox 的异步恢复、Servlet/WebFlux 生命周期以及两套上下文的隔离验证。

以下保留最初按 correlation 文本及 `ExecutionContext` / `MessageDescriptor` 构造调用生成的引用盘点。它是后续设计的检索起点，**不再代表待删除字段的修改清单**；具体修改文件仍须按实现接缝收敛：

| 分类 | 文件数 | 解释 |
|---|---:|---|
| 运行 Java | 22 | 包含 Gateway、Kernel、Web/Auth、消息组件、Order/Inventory 出站和 Notes 示例 |
| JVM 测试 | 43 | 同时包含行为断言和构造参数夹具；实际调整取决于消息 carrier 与测试接缝，不代表 43 项新增功能 |
| 公共测试支持 Java | 1 | InboundMessageContractTck 的关联断言与夹具 |
| 运行资源 | 4 | 2 份消息 Schema，以及 PostgreSQL/MySQL 各一份现有消息 V1 迁移；不是已决定改写这两个 V1 文件 |
| Python 验证 | 2 | 公共 HTTP 黑盒和其契约测试 |
| 示例说明 | 1 | Notes README |

合计 73 个当前非历史引用文件。另识别出 12 个 benchmark 历史文件，明确排除整体改写。完整路径与命中行见 [TRACE-IMPACT-INVENTORY.tsv](TRACE-IMPACT-INVENTORY.tsv)。

数量来自 `framework services apps examples verification` 下可见源码的文本/构造器调用盘点，模式为 `correlation|new ExecutionContext\s*\(|ExecutionContext\.initiatedBy\s*\(|new MessageDescriptor\s*\(`，忽略大小写。它是引用清单，不是最终修改文件数，也不是测试运行结果。新增的 Agent 接入、日志组件、Trace 恢复以及启动配置不会被旧标识搜索完整发现，另列于后文。

### 相对全面退出 correlation 的减少项

| 部分 | 已确认范围变化 |
|---|---|
| Kernel 与业务调用 | 保留 correlation 及既有构造参数，不为日志大面积迁移；框架统一生成/传播/投影，业务不适配特定 commandId |
| HTTP | 保留 Header/Problem 字段；仍需根入口信任、内部继承、X-Trace-Id 与完整生命周期 |
| 消息与夹具 | 保留 correlation 读写/断言；新增 Trace carrier 仍可能修改公共模型、构造调用与 schema |
| Outbox | 原 correlation 列复用；Trace、发布代次、原子 redrive、恢复与真实数据库证据仍须实现 |
| 输出、Agent 与隔离 | 工作基本保留；全局处理使责任集中在框架，没有省掉不同运行边界的实现 |

因此减少的是废弃 correlation 引发的迁移波及面，不能把原 73 个引用文件减成一个未经实施盘点的数字，也不能据此估算大幅减少工时。

## 2. 需要核对的现有链条

| 范围 | 当前证据与设计问题 | 对外或跨模块影响 |
|---|---|---|
| Kernel 业务上下文 | [ExecutionContext](../../framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContext.java) 第 6、20 行包含 correlation component 与构造入口。已确认保留关联元数据及既有非空校验；身份建立前另有请求诊断状态。 | 不因日志接入删除 correlation 或改成 Optional，省去对应的构造参数连锁迁移。业务身份与 Trace 分开，Kernel 保持不依赖 OTel SDK。现有代码仍为 TenantId 模型，Platform/ExecutionScope 的新增属于相邻专题。 |
| Servlet HTTP | [CorrelationIdFilter](../../framework/starters/moduvera-web-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/web/CorrelationIdFilter.java) 第 15–26 行建立旧 Header/request attribute；[Web 自动配置](../../framework/starters/moduvera-web-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/web/autoconfigure/ModuveraWebAutoConfiguration.java) 注册旧 Filter。当前 UUID / Header 机制没有自动定义业务会话生命周期。 | 用实际 OTel 上下文支撑 `X-Trace-Id` 回写；根入口建立独立关联，内部请求继承；保留旧头作为业务关联载体，公网生成、内部继承，不能在每个服务都重新生成。Servlet 的日志记录与身份清理次序要一起验证。 |
| Auth 与错误输出 | [ExecutionContextFilter](../../framework/starters/moduvera-auth-resource-server-autoconfigure/src/main/java/io/github/ande1922/moduvera/security/web/ExecutionContextFilter.java) 第 25–43 行依赖 correlation resolver；[ApiExceptionHandler](../../framework/starters/moduvera-web-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/web/ApiExceptionHandler.java) 第 87–92 行、[SecurityProblemWriter](../../framework/starters/moduvera-auth-resource-server-autoconfigure/src/main/java/io/github/ande1922/moduvera/security/web/SecurityProblemWriter.java) 输出旧错误体字段。 | 保留 correlation 错误字段，统一 resolver 和请求诊断状态，补充 X-Trace-Id。认证之前产生的 401/403 同样要有诊断关联；Trace 不参与认证或 Tenant 授权。不新增 Trace 正文字段；结束日志读取可信身份快照。 |
| Gateway / 远程 HTTP | [CorrelationGlobalFilter](../../apps/gateway-app/src/main/java/io/github/ande1922/moduvera/reference/app/gateway/CorrelationGlobalFilter.java)、[OrderSessionGatewayFilter](../../apps/gateway-app/src/main/java/io/github/ande1922/moduvera/reference/app/gateway/OrderSessionGatewayFilter.java)、[IdentityTokenExchangeClient](../../apps/gateway-app/src/main/java/io/github/ande1922/moduvera/reference/app/gateway/IdentityTokenExchangeClient.java)、[CatalogLookupTransport](../../services/order/order-service/src/main/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/CatalogLookupTransport.java) 显式读写或传参旧标识。 | WebFlux 与 Servlet 分别接入；远程客户端依靠受验证的标准传播。保留 correlation Header，Gateway 公网输入由本入口值覆盖；完整 WebFilter 生命周期还需覆盖未匹配路由和认证前失败。 |
| 消息模型 / 信封 | [MessageDescriptor](../../framework/foundation/moduvera-message-core/src/main/java/io/github/ande1922/moduvera/message/MessageDescriptor.java) 第 19、33 行强制 correlation；[KafkaMessageMapper](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/KafkaMessageMapper.java) 第 111、166 行写入/必读 `correlationid`。 | 公共 Java 模型新增纯值 creation Context；业务关联字段名称与必填约束保留，消息构造夹具仍可能调整。命令 Schema 禁止额外字段，若在信封新增 Trace 字段需显式登记。MessageId、causationId、payload、destination、partitionKey 的语义保留。 |
| 消息出站 / 入站 | Order、Inventory 和 Notes 的发布适配器从业务上下文取 correlation 构造消息；[ReliableInboundEndpoint](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/ReliableInboundEndpoint.java) 第 56–67 行将 correlation 恢复到业务身份上下文。 | 发布时捕获诊断上下文，并按选定业务规则保留适用的业务关联；消费时分别恢复 Trace、业务关联和可信身份。业务 Application 接口和消息 payload 不因此增加 Trace 参数。Inbox 继续使用 MessageId 去重。 |
| Outbox 数据与恢复 | [JdbcOutboxStore](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/JdbcOutboxStore.java) 第 89–127 行写库、454–475 行重建消息；现有 PostgreSQL/MySQL DDL 都有 `correlation_id NOT NULL`。 | 需要持久化可恢复的 Trace 载体，贯通写入、读出、Relay、重试和重投；新增可空 creation/publication Trace 字段和默认 0 的非空 publication_generation，按整体方案持久恢复。业务关联按独立契约保存，不预设删除现有列；不能将旧任意字符串解释为有效 Trace Context。 |

## 3. 需要新增的接入与验证能力

这些接入责任独立于旧 correlation 字段是否变化；保留业务关联能力不能替代 OTel 的传播与恢复。

- **Agent 装载与可重复运行。** 固定待验证的 Agent 版本与制品来源，明确启动时如何取得 Agent 和注入配置。[run-topology.sh](../../verification/reference-product/harness/run-topology.sh) 第 31、142–150 行统一建立各 App 的 `JAVA_TOOL_OPTIONS`，是两拓扑启动的现有接缝。需覆盖 Gateway、Identity、Catalog、Order、Inventory 和 Monolith 的实际装配，以及独立 Notes 消费者。
- **镜像与开发启动。** [Dockerfile.jvm](../../build/docker/Dockerfile.jvm) 第 21 行有公共 Java 入口，但目前不提供 Agent 文件。已选择固定版本的外置 Agent，容器运行时只读挂载，本轮不为此内置到公共镜像；实际装载仍需验证。沿用 [ADR 0036](../adr/0036-share-runnable-app-image-construction-without-defining-deployment.md) 的构建/部署边界，运行参数仍归运行环境。不能以当前无须改业务代码推导为无需启动资产变更。
- **日志上下文投影。** 提供统一的 Trace 字段读取与 JSON 输出接缝，确保日志使用真实 `trace_id` / `span_id`；独立业务关联字段需按其已定义语义投影，未知时省略。已选择公共日志 Starter 与各边界 Adapter 分工，实际模块命名与最小依赖在规格整理中落实。canonical、业务错误分级与应用指标需要相应实现；未进入产品支持面的源场景不会因设计确认全部启用。
- **真实出站覆盖。** 验证 Gateway → Identity 换票、Order → Catalog、Order → Identity 服务令牌这三条真实 HTTP 路径。最后一条当前没有显式 correlation 参数，仍需确认 Agent 传播；[IdentityServiceTokenTransport](../../services/order/order-service/src/main/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/IdentityServiceTokenTransport.java) 因此不在旧字段命中清单中。服务令牌缓存 key 继续按既有身份/租户作用域，Trace ID 不进入缓存 key，也不为产生 span 强制重新换票。
- **持久化异步传播。** [JdbcDurablePublication](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/JdbcDurablePublication.java) 的 append 是捕获候选接缝；[OutboxWorker](../../framework/foundation/moduvera-message-core/src/main/java/io/github/ande1922/moduvera/message/outbox/OutboxWorker.java) 第 97–140 行逐消息发送是恢复候选接缝。具体实现可置于相应 Adapter，复用同一 OTel API/SDK；不先规定必须修改 framework-free Worker 的算法。验证业务线程已退出或进程重启后仍能恢复关系，且同批不同消息不串 Trace。按场景验证延续原 Trace 或新 Trace 加 Link，并验证同一业务关联可跨 Trace 保持。
- **两套上下文的组合验证。** 原 [ExecutionContextSnapshotTest](../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshotTest.java) 覆盖业务快照、复用线程、虚拟线程与退出清理，不证明 OTel 的同样行为。Kernel 测试保持框架独立；另在适配层验证同身份不同请求的 Trace 隔离、嵌套恢复、失败、取消和未采样场景。不要通过减少字段断言来削弱原隔离证据。

## 4. 数据库与消息版本的范围边界

本轮没有执行 DDL、清库、清 Kafka/DLQ 或删除历史数据。

- 当前两份 V1 保留，已选择追加迁移，不清库或覆盖历史状态。旧 pending/terminal 的 creation 未知就保留为空；有效 claim 下首次发送前初始化并持久保存 publication Context，验证 PostgreSQL 和 MySQL 的原子性与重启恢复。
- [ADR 0020](../adr/0020-version-message-contracts-in-message-type.md) 仍规定破坏性消息契约变更需更换 MessageType major。本方案新增可选 Trace 扩展并保留现有 major，消费者与 schema 先行更新；旧严格命令校验器不能直接读取新字段的边界已明确，兼容样本仍须验证。若实施引入破坏性必填或语义变化则使用新 major，不静默豁免 ADR，也不自动把所有 `.v1` 改成 `.v2`。Destination/Topic 不作为第二套版本系统。
- [ADR 0033](../adr/0033-separate-migration-definition-from-execution-policy.md) 的迁移定义归属和执行策略保持；正常生产启动的校验路径不改成隐式执行 DDL。
- 业务关联继续使用 `correlation_id`；完成标准不是全仓库搜索零命中。历史升级测试、Grill 记录和 benchmark 证据保留原有语境。

## 5. 必须协调的相邻专题

| 专题 | 当前契约 | 范围内需要协调的部分 |
|---|---|---|
| [ExecutionContext 传播 Spec](../../.scratch/execution-context-propagation/spec.md) | 当前 `needs-triage`，设计把 Correlation 放入模型；第 69 行模型、第 108/191 行隔离断言及 02/03/07/09/10 等票依赖它。 | 关联继续由 ExecutionContext 承载，对齐根入口生成/下游继承语义、非空约束和缺失值策略，不能机械删除。身份与业务关联隔离、Trace 隔离分别取得证据。现有 Scope/Platform/Tenant、捕获时点和恢复设计不因此推翻；不借此次改动实施该专题全部能力。 |
| [HTTP Problem Spec](../../.scratch/http-problem-contract/spec.md) 与其 [01 号票](../../.scratch/http-problem-contract/issues/01-unify-external-problem-details.md) | 当前均为 `ready-for-agent`；已同步公网生成/内部继承、正常 Header/body 一致性及异常下游正文透传规则。 | 实施时共用请求诊断状态；错误格式的其他收敛仍由原专题负责，不复制 Gateway 下游业务语义。 |
| [消息契约验证工作流](../agents/message-contract-verification.md) | 共享 TCK 文档仍要求 Correlation 进入 ExecutionContext。 | 同步 TCK 的身份与 Trace 证据归属；协议 kind/type/source/destination 负例、消费完成屏障及无业务副作用要求保留。 |

设计确认阶段同步日志主规范、决策记录、术语及本范围稿。随后按维护者 to-spec 请求整理[正式规格](../../.scratch/governed-observability/spec.md)，同步 HTTP 既有 Spec/票的关联 AC 和 ExecutionContext Spec 的共享接缝；相邻执行状态、接口草案与历史决策未改，未新建实施票或改运行代码。协调归属见整体方案第 9 节。

## 6. 实施后应有的证据

1. 若上下文模型变化，Kernel 与 Java 调用方相应迁移通过；业务关联缺失遵循最终入口与字段契约，且关联校验与身份校验分开。Kernel 保持 framework-free，可信业务身份缺失仍按原规则失败。
2. Servlet 与 Gateway 分别验证正常、未认证、无权限、参数错误与异常响应；`X-Trace-Id` 与当前 Trace 对应，业务关联与 HTTP 旧头遵循最终选定契约。Gateway 未匹配路由和认证前拒绝需覆盖完整请求入口，不能只验证命中路由后的 GlobalFilter；Servlet 同样使用真实过滤链验证 401/403。入站 Trace 信任策略确定后补其正反样例。
3. 消息 Schema/mapper round-trip、契约负例、独立可信身份恢复与 Trace 缺失/非法输入策略有证据；不能因为 Trace 格式变化绕过业务信封校验。
4. 真实 PostgreSQL/MySQL 写读与选定迁移策略通过；清除原线程上下文、独立 Relay、重启、不同 Trace 批处理、retry/redrive 均不丢失关联或串线，MessageId/因果/顺序与 Broker ACK 语义不变。
5. 公共测试支持和两个 Python 验证入口按最终契约调整；既有 correlation 断言被明确分类为业务关联或旧诊断机制，不能机械删除。[OrderApplicationIT](../../apps/order-app/src/test/java/io/github/ande1922/moduvera/reference/app/order/OrderApplicationIT.java) 第 505–509 行当前把 `correlationId != null` 混入 Catalog stub 的 trusted 判断，迁移时应拆成认证/租户校验和独立传播断言。覆盖同业务关联、多 Trace，以及同消息重投时消息身份不变的样例。
6. 挂载真实 Agent，分别通过微服务与业务核心单体的公共链路，验证正常、401/403、Kafka 恢复和跨请求隔离。若修改镜像，运行镜像验证；如调整 Maven Agent 参数，保留现有 JaCoCo 的注入与覆盖证据。

本稿保留前轮源码盘点并按已确认设计收敛影响；具体实现文件数尚未确定，未执行上述运行验证。

## 7. 已确认的范围收敛

[整体接入方案](INTEGRATION-DESIGN.md)中的以下选择已获维护者确认，运行接入仍未实施：

- 保留 correlation 字段与 HTTP Header/Problem；公网生成、内部继承，额外回写 X-Trace-Id。
- ExecutionContext 的 correlation 保持非空；认证前另有请求诊断状态，Trace 仍不进入 Kernel。
- Outbox 新增不可变 creation 和可替换 publication Context、发布代次；采用追加迁移，保留 V1 和既有数据。
- 信封新增可选 Trace 载体；兼容性需用实际 schema 与读写样本验证，遵守 ADR 0020。
- 固定版本 Agent 外置并由运行资产装载；公共镜像不为本轮单独内置 Agent。本地/验收使用 OTLP 接收端，日志 stdout、应用指标 Micrometer。
- Servlet/Reactor 生命周期、逐条消息恢复与清理、可信身份快照，以及真实运行验收均已列入整体稿。

业务 payload、订单/库存状态机、权限模型、租户隔离、Inbox 去重/事务所有权、Outbox claim/lease 与发布保证、拓扑选择、生产部署和整套观测后端均不因本次统一自动扩张。
