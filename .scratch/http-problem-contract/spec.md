Type: spec
Status: ready-for-agent

# 统一外部 HTTP Problem Details 契约

## Problem Statement

项目已经通过 ADR 0011 决定：成功响应保持未包装的原生资源 JSON，失败响应使用真实 HTTP 状态码和 RFC 9457 Problem Details。当前不同错误入口的实际字段仍有漂移：普通业务异常包含稳定业务码、Correlation ID 和字段级校验明细；Spring Security 入口包含 Correlation ID；Gateway 与 Identity 自有错误未完整提供同样的关联和校验信息。这使外部客户端仍需按错误来源编写分支，削弱了统一错误契约的价值。

## Decision Boundary

- 成功响应继续直接返回资源，不引入 `data`、`success`、`code: 0` 或通用 `Result<T>` 信封。
- 所有外部可观察失败继续使用真实 `4xx`/`5xx` 状态和 `application/problem+json`。
- RFC 9457 核心字段保持 `type`、`title`、`status`、`detail`；项目扩展字段统一为稳定的 `code`、`correlationId`，校验失败按需增加 `errors`。
- `X-Correlation-Id` Header 是关联标识的权威传输位置；自产 Problem Details Body 的 `correlationId` 取同一请求诊断状态。正常下游链路通过 correlation 继承保持 Header/Body 一致；异常下游返回不一致 C 时，Gateway 保留入口 Header 与原正文并记录传播契约失败，不重写业务正文。
- 按已确认的[受治理可观测性规格](../governed-observability/spec.md)，公网根入口生成新 correlation，不采信调用方自定值；受保护的内部入口继承有效值，缺失/非法只补建一次并记录 WARN。响应 `X-Trace-Id` 与请求生命周期由该专题承接，Problem 本轮不增加 `traceId` 正文字段。
- Gateway 只统一自身产生的认证、路由和会话错误，并透明传播提供方错误；不得重新实现业务错误语义。

## Acceptance Criteria

1. Gateway、Identity、Spring Security 与普通业务 Web 异常对外返回一致的必需字段和媒体类型。
2. 校验错误使用确定性排序的字段明细，每项至少包含 `field`、稳定机器码和安全消息。
3. 未认证、无权限、校验失败、不存在资源和业务冲突继续使用正确的 HTTP 状态码。
4. 公网请求忽略调用方 correlation，内部入口继承有效 correlation，缺失/非法只补建一次；自产 Problem 与正常透传链路的 Body/Header 保持一致。异常下游返回不一致 C 时，Gateway 保留入口 Header 与原正文并记录契约失败，不为满足相等断言重写正文。
5. 两种受支持拓扑通过公开 Gateway 路径执行同一组黑盒错误契约测试。
6. 成功契约保持未包装，`201 Location`、`200` 查询以及现有 API 版本路径不变。

## Non-goals

- 不引入成功响应信封或 HATEOAS/HAL。
- 不采用 `200 OK` 表示业务失败。
- 不把内部异常类型、堆栈或敏感认证信息暴露给客户端。
- 不在 Gateway 复制提供方 Controller 的业务错误映射。

## Evidence

- `docs/adr/0011-use-native-http-semantics-and-service-owned-api-versions.md`
- `framework/starters/moduvera-web-spring-boot-starter/.../ApiExceptionHandler.java`
- `framework/starters/moduvera-auth-resource-server-autoconfigure/.../SecurityProblemWriter.java`
- `apps/gateway-app/.../GatewayProblemWriter.java`
- `apps/identity-app/.../IdentityErrorHandler.java`
- `verification/acceptance/tests/test_reference_product.py`

## Comments

- 2026-09-06：按维护者已整体确认的可观测性接入方案同步入口 correlation 与 Gateway 透传例外；保留原状态及错误格式专题归属，尚未实施或验收。
