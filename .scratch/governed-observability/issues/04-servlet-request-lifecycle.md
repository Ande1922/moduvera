Type: issue
Status: ready-for-agent
Blocked by: 02

# 04 — Servlet 请求诊断完整生命周期

## Outcome

Servlet 正常、安全拒绝及异步终止通过同一请求诊断状态输出准确关联、可信身份与一次完成记录。

## Spec coverage

[正式 Spec](../spec.md)：US03–07；ID02/03；TD02。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[02](./02-ecs-logging-and-context-projection.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

Web、Resource Server、Identity 等现有 Servlet 入口和异常处理器复用一份 request attribute 状态：C、单调起点、server Context、可信快照和完成标记。受保护装配决定公网/内部入口，错误处理只衔接当前请求关联。

Servlet 最终 ERROR 的本轮接入边界为现有 Spring MVC `ApiExceptionHandler` Advice：同步和异步 MVC 未知异常在此记录一次安全 cause，并在响应尚未提交时返回安全 500 Problem（`system.unexpected`、同一请求 C）。既有 Coded、校验、Spring 状态映射与 Security 401/403 专用处理器保持其原有响应和记录语义；本轮不提供 domain code 到日志失败类别的映射，也不宣称既有 Coded 依赖错误已经纳入新增最终 ERROR。原生 Servlet/Tomcat 容器日志不纳入最终 ERROR 次数及请求投影验收，不安装 Valve 或 AOP；这些请求仍须通过 canonical、真实响应状态及上下文清理验证，证据分析器单列其原生 ERROR。

## Acceptance criteria

- [ ] 所有公网请求（含查询）创建新 UUID v4 C，不采信或记录调用方 correlation；内部合法 C 原样继承，缺失/非法只补建一次并 WARN，不因诊断字段单独拒绝业务。
- [ ] 内部判定来自受保护装配/路由，不信任自报 Header；保持身份/tenant 缺失时原有 fail-closed 规则，认证前不制造 ExecutionContext。
- [ ] 认证、安全拒绝和异常处理读取同一状态，不分别 UUID 兜底；认证后保存可信日志快照，正常可写响应具有 X-Correlation-Id/X-Trace-Id，自产 Problem.correlationId 等于本请求 C。
- [ ] 合法/非法/未采样 W3C Trace 使用标准 Agent 关系；只回 X-Trace-Id，不回完整 traceparent，不增加 Problem traceId。
- [ ] 同步成功、401/403、未匹配路由、异常和 async complete/error/timeout/redispatch 竞争仅在真实终止时记录一次 canonical；初次 Filter 返回、1xx 或预设 200 不视为完成成功。
- [ ] canonical 使用捕获的 server Context/可信身份及完整生命周期单调耗时；终止线程无活动 Span 仍可正确记录。最终状态/传输失败/取消据实际事实分类。
- [ ] 不跨异步等待持有线程 Scope；各回调退出恢复原状态。响应已提交/客户端断开不宣称未实际送达的 Header/状态已送达；不缓存正文以计算日志长度。
- [ ] 现有未包装成功响应、真实 HTTP 状态及 Problem 正文语义保留；INFO/WARN/ERROR 责任与次数、安全字段通过真实输出验证。

## Verification

- [Web 自动配置测试](../../../framework/starters/moduvera-web-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/web/ModuveraWebAutoConfigurationTest.java)
- [Web 异常测试](../../../framework/starters/moduvera-web-spring-boot-starter/src/test/java/io/github/ande1922/moduvera/web/ApiExceptionHandlerTest.java)
- [认证上下文测试](../../../framework/starters/moduvera-auth-resource-server-autoconfigure/src/test/java/io/github/ande1922/moduvera/security/web/ExecutionContextHandlerInterceptorTest.java)
- [关联 resolver 测试](../../../framework/starters/moduvera-auth-resource-server-autoconfigure/src/test/java/io/github/ande1922/moduvera/security/web/DefaultRequestCorrelationIdResolverTest.java)
- [Security Problem 测试](../../../framework/starters/moduvera-auth-resource-server-autoconfigure/src/test/java/io/github/ande1922/moduvera/security/web/SecurityProblemWriterTest.java)
- 扩展真实 Servlet 容器/安全链与 async 终止测试，mock request 不能独立证明实际终止。先窄测，再 `./mvnw -pl framework/starters/moduvera-web-spring-boot-starter,framework/starters/moduvera-auth-resource-server-autoconfigure,apps/identity-app -am verify`；本票增加必要 HTTP 断言，双拓扑整链在 17 收口。

## Exclusions

不承担 HTTP Problem 专题的 type/title/detail/code/errors/媒体类型整体收敛；只完成现有错误路径的请求关联衔接。不依赖该专题整票，不修改其状态；共享文件由后续协调者安排单写者。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
- 2026-09-06：当前实施基线已包含 ExecutionContext 交付；旧 FilterTest 已由 HandlerInterceptorTest/HttpExecutionBoundaryIT 接缝替代，校正活测试链接，不改变已确认入口生命周期验收。

- 2026-09-06：前置 02 已经独立双轴评审并快进集成，直接前置均已交付，本票解除阻塞；不在第二批 02/14 的实施范围内，未认领或开始。
