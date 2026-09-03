Type: finding
Status: confirmed
Severity: major
Area: framework
Claim: correlation/tenant 没有进入 MDC 或分布式 Trace，仓库缺少受治理的 Tracing/OTel 能力。
Evidence: 产品面将 governed observability 列为 Planned；生产代码未见 MDC、OpenTelemetry 或 micrometer-tracing 集成。Order 测试中的 ObservationRegistry 只能证明观测注册表存在，不能证明上下文进入日志或跨服务 Trace。
Verification: 在 framework、apps、services 的 Java、pom.xml 与 application 配置中搜索 MDC、ObservationRegistry、opentelemetry、micrometer-tracing，并沿 HTTP 与消息入口检查 correlationId、tenantId 是否写入日志或 span。
Planned: .scratch/governed-observability/spec.md

# 缺少受治理的可观测性

## Verdict

Claim 确认成立。生产代码没有统一的 MDC、Tracing 或 OpenTelemetry 集成，现有 `ObservationRegistry` 仅出现在应用测试中，不能证明 correlation、tenant 或消息上下文进入日志与跨服务 Trace。产品面已经把 governed observability 列为 Planned，因此这是待完成的产品能力而不是现有 Supported 能力的 regression。

维护者确认接收并要求建立独立 topic，结合其既有日志规范再决定日志字段、上下文、脱敏、Trace 和指标治理方案。在日志规范进入该 topic 前，不预先固定 Starter 形态或遥测实现组合。
