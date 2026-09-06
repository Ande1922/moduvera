Type: issue
Status: resolved
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

- [x] 固定版本、下载来源、SHA-256 和 OTel API 依赖组合可复现；不用 latest，摘要不匹配、缺失制品或错误装载在受治理业务就绪前失败。容器只读挂载外置制品，公共镜像不内置 Agent。
- [x] 运行中只有 Agent 提供的一个 SDK；传播设为 tracecontext，Trace exporter 为 OTLP 且显式 http/protobuf，logs/metrics exporter 均为 none；不附加 tracing bridge 或第二套 SDK。
- [x] 真实 HTTP/Kafka 调用可观察 Agent 的有效 Context、传播 header 与 Span 数量/owner；验证无上游、合法/非法上游及未采样有效上游，不把全部 consumer 强制断言为同 Trace。
- [x] 验收根 Trace 全采样；普通库级测试仍可无 Agent，不能借伪造 Trace ID 满足受治理运行断言。已有 JaCoCo javaagent 与 Agent 同时生效。
- [x] 接收端启动后失联时，业务 HTTP/数据库事务及 Kafka ACK 仍按原契约完成；导出有界、异步，不等待遥测确认、不因遥测失败重放业务。记录所用队列/超时等有效配置及故障观察。
- [x] 自动采集不输出凭据、原始 Header、query/带 query URL、SQL 全文或 HTTP/消息正文；使用敏感哨兵夹具核对实际导出，不能只检查配置字符串。

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

## Answer

### Initial implementation qualification

- Commit: `a0c5ab9e9c286abbed00c4af9894767f98be0ca2`；实际比较点 `cb6602f2eb9cb9ef73c24ee87d1b6a069b083d6b..a0c5ab9e9c286abbed00c4af9894767f98be0ca2`，普通实现及修复提交已快进集成到 `codex/governed-observability-20260906-integration`，无冲突。
- 验证通过（PASS）：JDK 26 / Boot 4.1.1 全 reactor `clean install`、6 个公共镜像 contract、外置只读 Agent/扩展与 JaCoCo 真实镜像 smoke、真实 HTTP/Kafka/数据库/OTLP 验证。Agent 2.31.1 / API 1.65.0、来源和 SHA-256 已固定于 [agent.lock](../../../verification/governed-observability/agent.lock)。重命名重复 Agent、缺失/错误摘要、校验和有效但 SPI 失败的负例均在业务 main/就绪前被拒绝。
- 375 次 OTLP 请求、724 个 Span、711508 原始字节中，6 类敏感哨兵零命中；采样订单绑定 11 个核心 Agent Span 与 2 条 Kafka 记录，合法上游 Gateway→Identity 另验证 3 个具体 Span 的数量、owner 与因果。缓存实测 Identity 请求 1→0、Catalog 1→1；未采样有效 Trace 继续传播。
- OTLP 接收端停机后订单最终 CONFIRMED，并保留该订单的 reserve/result Kafka、两侧 Inbox/Outbox 和库存/订单证据；0.307175 秒仅为创建 HTTP 返回时间，终态在后续观察。限定本次故障运行的一次有效业务结果，保持至少一次传输契约。
- Standards 和 Spec 均在上述同一 base/head 完成且无剩余 finding：双轴评审记录：`/private/tmp/governed-observability-frontier-20260906/evidence/01/review-final.md`。完整运行见 修复验证报告：`/private/tmp/governed-observability-frontier-20260906/evidence/01/worker-fix-report-round1.md`；最后提交仅补分析器及其回归，最终分析重放：`/private/tmp/governed-observability-frontier-20260906/evidence/01/worker-fix-report-round2.md` exit 0，运行制品和配置未改变。集成后 `verification/governed-observability/tests/test-agent-contract.sh` 再次 PASS（10 tests）。
- 本票基线能力已交付；本批 01→07 的聚合双轴评审、Normal Gate 与最终适用 Scenario 将在 07 集成后的固定提交完成，不将本票证据冒充批次最终验收。

### Gate follow-up qualification

- 当前票提交：`237a20180d9b15181e4f48ee9b0b07ed97b97e7e`；B0 沿用 `cb6602f2eb9cb9ef73c24ee87d1b6a069b083d6b`。普通后续提交 `7bec10092a05219c00040a3786e144eb2fcf9ec3` 与 `237a20180d9b15181e4f48ee9b0b07ed97b97e7e` 由原 writer 在原 worktree 完成；经两轴 clean 复审后，以本地 merge `1a3942adeb6403600fdf845c8210c0dc6784519b` 纳入专用 integration，无冲突。07 代码与提交未改变。
- 首次聚合 Normal Gate 在 `a228740865e44cf4692e5f63357d37f366078ce9` 的 sensitive-content 阶段退出 1，Maven 尚未运行；失败回执保留于 `/private/tmp/governed-observability-frontier-20260906/evidence/final/gate-attempt1/normal-gate-receipt.json`。
- 修复验证输入：运行时生成 Java URL userinfo；登录 credential 显式注入且限定两个 probe 子进程，处理继承 export 属性碰撞；在启动/HTTP 前拒绝非 canonical tenant-a；文档区分 credential 与业务标识留存。没有修改生产认证、seed、业务行为或 Agent 配置。
- 两轴最终 clean：`/private/tmp/governed-observability-frontier-20260906/evidence/01/review-gate-fix-final.md`。原 writer 的两个报告为同目录 `worker-gate-fix-report.md`、`worker-gate-fix-round2-report.md`；准确 B0..当前票提交的 sensitive check exit 0。受影响 Java 测试/Spotless PASS；集成后静态契约 13 tests 与 shell 环境隔离回归 PASS，回执：`/private/tmp/governed-observability-frontier-20260906/evidence/final/agent-static-after-gate-fix/receipt.json`。
- 上节真实 runtime qualification 保留为凭据输入修复前的证据；本次只重新验证受影响的 fixture/环境输入边界，未声称新的真实登录运行。此 tracker 提交之后将固定批次最终 head，再做聚合双轴评审、Normal Gate、镜像与双拓扑验收；最终回执外置保存，不预填 PASS。
