Type: issue
Status: blocked
Blocked by: 02

# 05 — Gateway 响应式请求诊断

## Outcome

Gateway 从公网进入到 Reactor 实际终止保持一份诊断状态，覆盖路由前拒绝与错误，并透明保留下游正文。

## Spec coverage

[正式 Spec](../spec.md)：US03–08；ID03/04；TD02/03。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[02](./02-ecs-logging-and-context-projection.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

在能覆盖安全拒绝和未匹配路由的 Gateway WebFlux 生命周期接缝建立诊断状态，使用 exchange/Reactor Context 传递，不能只依赖匹配路由后才执行的 GlobalFilter。每次局部同步回调短暂恢复所需 Scope。

## Acceptance criteria

- [ ] 公网根创建新 C，忽略调用方 C，并向内部注入；不能因请求自报内部而绕过根规则。合法 W3C Trace 默认由 Agent 接续，不把诊断字段作为身份或权限。
- [ ] 正常、认证前 401/403、未匹配路由、自身异常及取消均读取同一状态；可写响应具有双 Header，自产 Problem 的 C 一致，未认证字段省略。
- [ ] 下游正文透明转发；下游 C 异常不一致时保留入口 Header 与原正文，记录传播契约失败，不为相等重写正文或增加 Problem traceId。
- [ ] Reactor 实际完成/失败/取消只记录一次 canonical；记录捕获的 server Context/可信快照及真实耗时，不以 beforeCommit 或最初返回作为完整响应终止。
- [ ] 跨 scheduler、不同订阅、同 tenant 不同请求、retry/error/cancel 均不串业务身份、C、Trace 或 MDC；缺失键不能借用线程偶然值，回调退出恢复原状态，不全链持有线程 Scope。
- [ ] 响应已提交、客户端断开及无状态传输失败据实记录；不以预设状态推断成功，安全 cause/字段和恢复/最终错误次数符合活规范。

## Verification

- [GatewayApplicationIT](../../../apps/gateway-app/src/test/java/io/github/ande1922/moduvera/reference/app/gateway/GatewayApplicationIT.java)
- [GatewayMonolithTargetIT](../../../apps/gateway-app/src/test/java/io/github/ande1922/moduvera/reference/app/gateway/GatewayMonolithTargetIT.java)
- 真实 Reactor 订阅/调度与 HTTP 客户端断开夹具证明完成、错误、取消竞争及 Scope 恢复；01 的 Agent/导出端核对 parent/Span 数量。先窄测，再 `./mvnw -pl apps/gateway-app -am verify`。共享上下文组合来自 02，本票拥有局部 Reactor 生命周期适配，不依赖完整 Reactor/AI 传播专题。

## Exclusions

不统一下游业务 Problem 内容，不扩张平台认证能力、公开路由或长连接产品支持；不把 Servlet 票 04 作为无必要前置，双入口拼接由 17 验证。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
