# Moduvera 日志后续接入

本文件记录实施与后续演进责任，不是活规则或完成报告。已采用的语义见 [LOGGING.md](LOGGING.md)，已确认接入设计见 [INTEGRATION-DESIGN.md](INTEGRATION-DESIGN.md)，落地状态见 [ADOPTION.md](ADOPTION.md)。

首版直接使用 OpenTelemetry Java Agent 及[整体接入方案](INTEGRATION-DESIGN.md)已于 2026-09-06 完成整体确认。下列工作是后续实施与验证责任，运行能力仍未完成。

- [受治理可观测性专题](../../.scratch/governed-observability/spec.md)已按后续 to-spec 请求整理完整规格与测试接缝，并同步 HTTP Problem 既有 Spec/票和 ExecutionContext Spec。规格为 needs-triage 待审阅；批准且明确请求下一阶段后再拆票，当前未新建实施票或修改运行代码。
- 已确认没有外部业务消费者的兼容约束，以及技术追踪与业务关联分工、根入口生成独立 correlationId 并由执行上下文与持久消息承载的[语义决定](decisions/TRACE-AND-BUSINESS-CORRELATION.md)。[改动范围](TRACE-CONVERGENCE-SCOPE.md)已撤销全面删除 correlation 的预设；入口信任、字段约束、Trace 重放、Actor / Initiator 投影与 HTTP 无状态码失败分类已整体确认并同步主规范与 Java 绑定，运行实现仍需相应证据。
- 验证上下文的捕获、安装和恢复，尤其是 Reactor、虚拟线程、线程池复用及持久化消息重新发布；不得把源项目的传播组件视为本项目已支持的能力。
- 健康检查等高频 canonical 的排除与采样、`error.code` / `event.action` 集中登记，沿用源规范的延期状态，需有明确需求再收敛。
- 集中采集与检索、生产 Collector / Trace / 指标后端、告警阈值、SLO、retention、独立审计通道及部署资产不随文档采用进入实施范围。
