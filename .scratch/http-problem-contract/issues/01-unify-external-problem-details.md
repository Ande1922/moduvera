Type: issue
Status: ready-for-agent
Blocked by: None — 基于现有 HTTP/认证接缝独立收敛错误契约，无前置实现票。

# 01 — 统一外部 RFC 9457 Problem Details

## What to build

收敛所有外部可观察错误入口，使 Gateway、Identity、Spring Security 和普通业务 Web 异常遵守同一个 RFC 9457 契约，同时保持成功响应未包装。

## Scope

- 统一 `type`、`title`、`status`、`detail`、`code` 和 `correlationId` 的存在条件与语义。
- 统一请求参数和请求体校验的 `errors` 结构、机器码及确定性排序。
- 自产 Problem 与正常透传链路的 Body / `X-Correlation-Id` Header 使用同一请求关联；公网生成新值，受保护的内部入口继承有效值，缺失/非法只补建一次并 WARN。
- 只规范 Gateway 自身产生的错误；下游业务 Problem Details 保持透明传播。
- 异常下游返回不一致 correlation 时保留入口 Header 与原正文并记录传播契约失败，不重写正文。请求诊断状态、Trace Header 与生命周期由[可观测性专题](../../governed-observability/spec.md)承接；实现共享接缝时协调，Problem 不增加 Trace 正文字段。
- 增加 Web Starter、Security、Gateway、Identity 的聚焦契约测试，并扩展双拓扑黑盒验收。
- 更新受影响的公开契约文档，但不改变 ADR 0011 的未包装成功响应决策。

## Done when

- [ ] 所有对外错误响应使用正确非 2xx 状态和 `application/problem+json`。
- [ ] 必需 Problem Details 字段在各错误来源间一致。
- [ ] 校验失败稳定返回字段级 `errors`。
- [ ] 公网 correlation 不采信调用方值，内部有效值继承、缺失/非法只补一次；自产与正常透传 Problem 的 Header/Body 一致，异常下游不一致时保留入口 Header 和原正文并记录契约失败。
- [ ] 成功响应仍无 `data` 或 `success` 包装，`201 Location` 保持外部路径。
- [ ] 聚焦模块测试与双拓扑公开 HTTP 验收通过。

## Comments

- 2026-09-03：讨论确认不引入成功信封；该 TODO 仅统一现有 RFC 9457 失败契约。
- 2026-09-06：同步已确认的入口关联和透传例外，补齐既有票的 `Blocked by: None` 元数据；未新建票、推进执行状态或修改运行代码。
