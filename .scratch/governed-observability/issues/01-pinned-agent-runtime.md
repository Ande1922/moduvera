Type: issue
Status: claimed
Blocked by: None

# 01 — 固定 Agent 装载与真实 Trace 导出

## Outcome

开发与验收通过可复现的外置 Agent 启动真实应用，获得单一 SDK 的 HTTP/Kafka Trace，并验证启动失败及遥测后端失联的行为。

## Spec coverage

[正式 Spec](../spec.md)：US10（自动埋点基线）、US20；ID10；TD09。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：无。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

在现有启动、验证与容器装配接缝准备外置 Agent、验证专用 OTLP 接收端及最小运行探针。先在 JDK 26 / Boot 4.1.1 上取证，再锁定 Agent/API 组合与 SHA-256；不预填未经验证的版本。为后续各票提供可复用的真实进程、record headers 和导出 Span 观察方法。

## Acceptance criteria

- [ ] 固定版本、下载来源、SHA-256 和 OTel API 依赖组合可复现；不用 latest，摘要不匹配、缺失制品或错误装载在受治理业务就绪前失败。容器只读挂载外置制品，公共镜像不内置 Agent。
- [ ] 运行中只有 Agent 提供的一个 SDK；传播设为 tracecontext，Trace exporter 为 OTLP 且显式 http/protobuf，logs/metrics exporter 均为 none；不附加 tracing bridge 或第二套 SDK。
- [ ] 真实 HTTP/Kafka 调用可观察 Agent 的有效 Context、传播 header 与 Span 数量/owner；验证无上游、合法/非法上游及未采样有效上游，不把全部 consumer 强制断言为同 Trace。
- [ ] 验收根 Trace 全采样；普通库级测试仍可无 Agent，不能借伪造 Trace ID 满足受治理运行断言。已有 JaCoCo javaagent 与 Agent 同时生效。
- [ ] 接收端启动后失联时，业务 HTTP/数据库事务及 Kafka ACK 仍按原契约完成；导出有界、异步，不等待遥测确认、不因遥测失败重放业务。记录所用队列/超时等有效配置及故障观察。
- [ ] 自动采集不输出凭据、原始 Header、query/带 query URL、SQL 全文或 HTTP/消息正文；使用敏感哨兵夹具核对实际导出，不能只检查配置字符串。

## Verification

- [现有双拓扑启动 harness](../../../verification/reference-product/harness/verify.sh)
- [现有镜像验证入口](../../../verification/application-image/verify.sh)
- 在测试/验收资产中增加真实进程与验证接收端的最小 smoke；保留完整启动命令、版本/摘要、Span 与 headers 样本、故障注入和退出结果。此票只验证基线自动埋点，不声称后续业务生命周期埋点已完成。

## Exclusions

不选择生产 Collector、存储、告警、SLO、采样比例或部署方式；不提前实现 HTTP 完成日志、Outbox 发布代次或指标目录。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
- 2026-09-06：维护者授权首批 01 → 07 的隔离实施；协调者为票 01 预留专属 writer/worktree，基线与后续证据记入 execution-ledger。
