Type: issue
Status: blocked
Blocked by: 02

# 06 — HTTP 出站传播与尝试结果

## Outcome

真实 Gateway→Identity、Order→Catalog/Identity 调用保留 Agent 自动传播，输出真实可观察尝试结果，且不改变服务令牌缓存行为。

## Spec coverage

[正式 Spec](../spec.md)：US02（出站责任）、US10；ID01/02/03/10；TD01/09。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[02](./02-ecs-logging-and-context-projection.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

在当前 HTTP client 与 token exchange/client 接缝补足规范要求的关联与结果日志。业务边界按关系分类，Agent 拥有标准传播与 client Span；框架只补本地日志/业务关联，不复制技术埋点。

## Acceptance criteria

- [ ] 三条实际出站路径使用当前 C 和 Agent 标准 traceparent/tracestate，未采样有效 Context 继续传播；client/server Span 无重复同语义 owner。
- [ ] 每次应记录的可观察实际尝试有结果 INFO，结果为 success/failure/unknown，耗时使用单调时钟；连接/DNS/超时等已观察失败不因无状态码写为 unknown。
- [ ] 恢复 WARN 只在实际恢复或确定再试时记录，耗尽不再宣称将重试；最终 ERROR 由最终责任方记录，client 不抢先重复记录，code/cause 安全。
- [ ] 服务令牌缓存命中无额外网络请求；C/Trace 不进入缓存键，也不改变认证权限、缓存失效或 client 错误语义。
- [ ] 日志和自动埋点不输出 token、原始 Header/query/正文，已知字节数才记录；不为日志读取/缓存正文，不编造底层不可观察重发次数。

## Verification

- [CatalogHttpClientTest](../../../services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/CatalogHttpClientTest.java)
- [IdentityServiceTokenProviderTest](../../../services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/IdentityServiceTokenProviderTest.java)
- [ServiceTokenCacheTest](../../../services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/adapter/outbound/http/ServiceTokenCacheTest.java)
- [GatewayApplicationIT](../../../apps/gateway-app/src/test/java/io/github/ande1922/moduvera/reference/app/gateway/GatewayApplicationIT.java)
- 以 01 的真实 Agent 运行现有 client 与真实 HTTP 对端，核对 requests 数量、headers、Span 和 stdout；通过现有可信上下文接缝建立调用状态，本票不要求 04/05 的完整入口生命周期。先窄测，再 `./mvnw -pl services/order/order-service,apps/gateway-app -am verify`；公网到内部的完整拼接在 17。

## Exclusions

不新增 Service API/DTO 或网络请求，不改 token 缓存策略和授权；不手写第二套 HTTP tracing instrumentation。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
