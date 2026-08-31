# Grill 会话完整复盘

## 范围与证据

事实源是 ChatGPT 会话“重新定义脚手架目标”。公开分享快照仍停在 Q380；本轮又从同一账号下的原始会话读取了 33 条新增最终消息，合并后本地证据集包含 418 条用户消息或助手最终回复、Q1-Q424 全部问题编号，以及 483 次提问出现。出现次数大于编号数，是因为多处问题被重复提出或改写。完整原文保存在 [grill-session-transcript.md](./grill-session-transcript.md)，问题出现位置列在 [question-index.md](./question-index.md)。

助手给出的推荐不等于决策。本次复盘分别标记用户明确确认、仅继续推进、需要实证、明确延期以及被后续结论覆盖的内容。

This file is a historical recap of the original Q1-Q424 Grill and intentionally preserves what that session concluded at the time. Current implementation must follow [DECISION-LEDGER.md](./DECISION-LEDGER.md) and the ADRs: ADR 0022 keeps multi-process microservices as the Golden Path while requiring focused modular-monolith topology support, MyBatis-Plus is selected with PostgreSQL primary and MySQL compatibility, Spring Cloud Stream with Kafka replaces equal multi-broker support, and lightweight contracts target one package-separated `platform-kernel` artifact.

## 设计树如何展开

| 阶段 | 问题范围 | 当轮 Frontier | 关键变化 |
|---|---:|---|---|
| 产品定义 | Q1-Q6 | 到底交付什么产品、如何验收 | 从组件清单转为强约束、可版本化、带可执行纵切面的黄金路径 |
| 技术栈、数据、租户 | Q7-Q19 | 可移植边界、租户范围、验证业务 | 数据库中立收敛到 Domain/Application；验证业务成为多租户餐饮连锁 SaaS |
| 身份、RBAC、服务 API | Q20-Q39 | 认证归属、浏览器/内部 Token、本地/远程调用 | 认证改为内部闭环，RBAC 回到首版；Opaque Token + 内部 JWT 取代浏览器大 JWT |
| 调用语义与轻量 CQRS | Q40-Q47 | Local/HTTP 一致性、事务、Modulith、Command/Query | Spring Modulith 被移出首版，微服务路径继续作为主要压力测试 |
| Web 与运行底座 | Q48-Q60 | Web 打包、HTTP/错误、配置、可观测、迁移、测试 | 多个公开 Web Starter 合并；原生 HTTP 语义胜过 Result 包装 |
| 消息核心 | Q61-Q77 | 事件分类、可靠性、信封、Schema、租户和 Trace | 自定义信封被否决，改为结构化 CloudEvents；Outbox/Inbox 确立至少一次语义 |
| 消息运维 | Q78-Q111 | 异步命令、双 Broker、DLQ、顺序、命名、可信生产者 | Message Type、Binding、Destination 被拆成不同角色；Broker 故障改为 DEGRADED 而非自动拒绝业务写入 |
| HTTP 韧性与一致性 | Q112-Q138 | 超时、重试、身份、幂等、分布式一致性、缓存、锁 | 通用 Command 幂等延期；自有 Local/Redisson Lock 语义取代框架 LockRegistry |
| 长事务协调 | Q139-Q144 | Process Manager、补偿、模块测试、AI 上下文 | 工作流运行时离开首版；保留 Maven、架构测试和渐进式 Agent 上下文 |
| 代码组织与领域边界 | Q145-Q190 | 包层级、Application Service、Repository、事件、类型、ID、Demo | Handler/Port 风格被修正为简化 DDD，聚合 Repository 回到 Domain |
| Demo 与交付定义 | Q191-Q204 | 双装配、验收门禁、实施顺序、版本基线 | Token 统计降级为实验项，决策开始使用显式状态 |
| 数据矩阵与数据库语义 | Q205-Q267 | 双适配器/双数据库、事务、迁移拓扑、文本与搜索 | “最终选一个”变成 2 x 2 维护矩阵；物理独库改为逻辑所有权；Binary Collation 改为字段级语义 |
| 缓存与任务 | Q268-Q306 | 缓存抽象、防击穿、Redis 角色、调度任务 | 自定义 CacheTemplate 被移除；所有本地缓存加载启用 Single-flight；虚拟线程调度改为有界调度器 |
| HTTP Client 与 API 契约 | Q307-Q356 | Client Group、韧性归属、App 装配、API 版本、校验、安全 | 运行策略由调用方拥有；Boot 原生 Client Group 取代自建 Factory；版本语义回到服务 |
| 对象存储 | Q357-Q374 | 上传、元数据、可见性、生命周期、引用、Checksum | 取消所有对象强制落库；对象元数据优先、业务记录按需；图片处理延期 |
| 测试与 CI | Q375-Q397 | Java 分层测试、pytest 黑盒验收、真实依赖、质量、覆盖率、Flaky Test | pytest 被纳入独立验收层；设备模拟器移出脚手架；PIT 只做可选定向 Profile |
| 构建与制品 | Q398-Q416 | Maven、依赖收敛、可复现、SBOM、漏洞、镜像 | 固定构建输入与 SBOM 保留；Trivy 镜像扫描、Dockerfile 和其他镜像链路按最新范围延期 |
| 部署与收口 | Q417-Q424 | Compose/Kubernetes/Helm、迁移、本地组合、滚动发布 | 历史对话形成了推荐，但当前用户已明确部署尚未确认；Q423 因重复撤销 |

## Grill 过程本身

这个会话反复做对了四件事：

1. 先解决前置决策，再进入实现选择。产品形态、所有权和边界早于框架细节。
2. 把异议变成修订分支。JWT 尺寸、Repository 归属、领域事件流、租户外键、事务风格、缓存抽象、调度器和对象元数据都因此被改写。
3. 用具体场景暴露隐藏语义：餐饮集团与门店、Local 与 HTTP、Broker 故障、重复消息、滚动数据库变更、缓存击穿、公开与私有预览。
4. 明确延期尚不能合理定案的设计：通用幂等、长事务编排、跨服务 Deadline、图片处理。

它也出现了可追踪的失败：问题编号复用、已定问题被重复询问，并在 Q334 左右把 Gateway 路由和 API 版本归属混为一谈。到 Q423 又重复询问已经确定的迁移回滚语义，用户明确指出重复并要求停止发散。助手撤销 Q423；本地问题索引保留全部重复与修订，没有把过程“洗平”。

## 最重要的修订

- 默认 PostgreSQL + RLS 改为可移植租户语义，数据库能力只做纵深防御。
- 只接外部 JWT 改为内部认证中心、租户 RBAC、浏览器 Opaque Token 和内部 JWT。
- `*-contract` 与协议绑定 Client 收敛为协议无关 `*-api`、本地 Application 实现和远程 Client Adapter。
- 自定义事件信封被明确否决，之后才形成有边界的 CloudEvents 契约。
- Spring Modulith、XA/Seata 和通用 Process Manager 被移出首版。
- Handler/Port 密集结构改为简化 DDD、Application Service 和 Domain-owned Aggregate Repository。
- “jOOQ/MP 对照后选一个”改为 jOOQ/MP x MySQL/PostgreSQL 正式矩阵。
- 方法级 `@Transactional` 改为显式本地编程式事务工作单元。
- 万能迁移 JAR 改为服务自有 Schema/Migration 制品，由部署集中编排。
- Binary 或全局 CI Collation 改为默认 AS+CS、身份字段规范化、人类搜索显式 CI。
- 自定义 CacheTemplate 改为 Spring Cache + CacheDefinition，集群分布式防击穿改为本机 Single-flight。
- 虚拟线程调度改为固定容量调度器及 fixed-delay/Cron。
- 自建 HTTP Client Factory 改为 Boot 原生 Client Group。
- Gateway 剥离版本改为服务拥有路径版本。
- `200 + Result<T>` 保持否决；后续 Problem Details 字段覆盖早期 type-only 方案。
- 所有对象强制落库改为对象元数据优先，只有业务拥有生命周期或授权时才建业务记录。
- 测试从 Java 内部层次扩展为独立 pytest 黑盒验收；设备模拟器不再属于通用脚手架。
- V0.1 暂按 PR、主分支、定时 CI 逐层扩大测试矩阵；该节奏仍是可逆假设。PIT 已确认仅在关键规则上按需运行。
- 构建保留可复现输入与 SBOM；Trivy 镜像扫描、显式多阶段镜像和 Cosign 都不进入当前非部署范围。
- Q417-Q424 的 Compose/Kubernetes/Helm 与滚动发布结论现已被重新打开，不再作为当前 Accepted 决策。实现阶段排除部署资产。
- 本地 MySQL + RocketMQ、PostgreSQL/Kafka 可切换 Profile 仍只是历史建议；开发/测试运行方式不能反推生产部署拓扑。

## 本次重建产物

- [CONTEXT.md](../../CONTEXT.md)：只放统一术语。
- [docs/adr](../adr)：只记录已经确认且满足 ADR 门槛的难逆转决策。
- [DECISION-LEDGER.md](./DECISION-LEDGER.md)：区分 Accepted、Provisional、Verify、Deferred、Open、Superseded。
- [OPEN-QUESTIONS.md](./OPEN-QUESTIONS.md)：Grill 收口状态与 V0.1 验证 backlog。
- [V0.1-VERTICAL-SLICE.md](../implementation/V0.1-VERTICAL-SLICE.md)：首个纵向切片和实施节奏。
- [grill-session-transcript.md](./grill-session-transcript.md)：完整本地证据。
- [question-index.md](./question-index.md)：Q1-Q424 的审计索引。

## 当前阶段

架构 Grill 已按用户要求结束。后续架构简化评审已经通过 ADR 0015-0018 收敛主路径；尚未被强确认的技术细节由真实业务切片和 TCK 产生证据后更新。部署单独保持 Open，不在实现过程中被默认补齐。执行边界见 [OPEN-QUESTIONS.md](./OPEN-QUESTIONS.md)。
