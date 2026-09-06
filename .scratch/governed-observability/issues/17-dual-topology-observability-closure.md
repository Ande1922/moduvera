Type: issue
Status: blocked
Blocked by: 03, 05, 06, 15, 16

# 17 — 双拓扑整链与接入说明收口

## Outcome

受治理微服务和业务核心单体以同一公共验收契约验证整链，汇总全部票的可追溯证据，并给出与实际装载一致的接入说明。

## Spec coverage

[正式 Spec](../spec.md)：US01–21（整链映射）、US21（双拓扑）；ID01–10；TD01–10（证据核对）。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[03](./03-in-process-task-context.md)、[05](./05-gateway-reactor-lifecycle.md)、[06](./06-http-outbound-diagnostics.md)、[15](./15-business-fact-logging.md)、[16](./16-independent-notes-qualification.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

扩展现有双拓扑公开 harness 的观测断言与证据索引，补齐 App 装配和说明中的最后接入缺口；底层数据库/重试/解析完整矩阵复用对应票证据。本票是实施与 Scenario 收口，不取代独立 Review、Quality Gate 或 Final Acceptance。

## Acceptance criteria

- [ ] microservices 与 business-core-monolith 都运行同一公开 HTTP 契约，实际 Agent 产生可验证日志/Trace；微服务的远程传播与单体本地调用各保持正确因果，不为本地每个 Java 方法强制 Span。
- [ ] 公网 C、内部继承、双 Header、自产 Problem C/透明下游正文、认证前拒绝、可信身份隔离及 Gateway→Identity、Order→Catalog/Identity 出站拼接通过。HTTP 其他错误字段整体收敛仍归相邻专题。
- [ ] HTTP→Durable→Relay→Kafka→Inbox/handler 和原消息 redrive 的 C/creation/publication/transport 关系与底层票证据一致；令牌缓存无额外请求，九项 meter 原义保持。
- [ ] 01–16 的验收矩阵覆盖全部 US01–21 与 TD01–10，所引用输出仍存在且适用于当前交付基线；不能用旧 worktree 的 resolved 状态或最初测试计划替代证据。
- [ ] 保留未采样、遥测后端失联、敏感哨兵、线程/消息隔离、真实终止与进程恢复证据；负向业务断言使用有效消费进度屏障，不以固定 sleep/发送 ACK 代替消费完成。
- [ ] 独立 Notes 证据与 Maven/架构边界可追溯；开发/验收装载、consumer-first、追加迁移、示例和排障说明与真实运行一致；Candidate/未支持任务、设备、AI、长连接范围明确。
- [ ] 记录实际命令、base/head 或显式未提交 diff、环境与证据位置；缺失/失败项如实保留，不自动关闭 finding、改变父 Spec 或宣称正式最终 PASS。

## Verification

- [双拓扑 harness](../../../verification/reference-product/harness/verify.sh)
- [公共 HTTP 黑盒](../../../verification/acceptance/tests/test_reference_product.py)
- [Product Surface](../../../docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md)
- 分别执行 `verification/reference-product/harness/verify.sh microservices` 与 `verification/reference-product/harness/verify.sh business-core-monolith`，归档真实 Agent/stdout/OTLP/registry 证据；依赖 16 的独立 Notes 消费证明。
- 按受影响装配先窄测，再 `./mvnw clean verify`；涉及镜像装配时按仓库要求补适用 image Scenario。正式 Review/Quality Gate/Final Acceptance 仍走后续独立阶段，不能把本票的 Maven/Scenario 通过写成正式最终验收通过。

## Exclusions

不部署生产观测后端，不新增审计/指标平台，不把 HTTP Problem/ExecutionContext 相邻专题整体并入；不自动 commit、merge、push、部署或提升产品/finding/父 Spec 状态。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
