Type: issue
Status: blocked
Blocked by: 04, 08, 09, 13, 14

# 16 — 独立 Notes 消费者接入证明

## Outcome

Notes 以自己的 Parent 和导入 BOM 使用公开组件，运行 HTTP→数据库→Kafka 链路，取得独立于参考业务装配的日志、Trace、指标及隔离证据。

## Spec coverage

[正式 Spec](../spec.md)：US21（独立消费者/依赖边界）；ID08/10；TD10。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[04](./04-servlet-request-lifecycle.md)、[08](./08-inbound-transport-causality.md)、[09](./09-immediate-ack-diagnostics.md)、[13](./13-atomic-redrive-generation.md)、[14](./14-micrometer-runtime-baseline.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

扩展现有 NotesDemoIT/启动说明和其公共依赖，不引入 Catalog/Order/Inventory/app-monolith 内部实现。依赖 09 验证实际一次性发送能力，依赖 13 验证完整持久发布/恢复与 08 消费组合；复用各票已完成的底层故障证据，聚焦独立装配与消费者行为。

## Acceptance criteria

- [ ] 保持独立 Spring Boot Parent、导入 BOM 和 versionless 公共依赖，通过实际构建及启动消费新增日志组件/Agent，不借仓库 Parent 的隐式配置掩盖缺失。
- [ ] 真实 HTTP 创建/读取、认证前错误、tenant 隔离与数据库提交保留原行为；stdout/双 Header/Span/C/可信身份关联可追踪。
- [ ] 真实 PostgreSQL/Kafka 的 Immediate 与 Durable/Outbox/Inbox 路径取得 envelope、transport 和消费因果证据；重试、去重及必要恢复组合仍有效。
- [ ] 实际 registry 更新九项指标且无重复 SDK 导出；公共组件不强制未使用运输的消费者引入全套 Servlet/Reactor/Kafka，Kernel 框架依赖边界通过。
- [ ] 说明固定 Agent 装载、公共配置、普通测试与受治理运行差异、升级顺序及未支持项；文档示例与实际运行命令一致，不移植源项目类名作为已交付能力。
- [ ] 验证记录绑定实际 checkout/commit 或明确未提交 diff、依赖坐标、命令、输出和基础设施；证据范围保持 Notes PostgreSQL，不扩张为 MySQL 或全场景支持。

## Verification

- [NotesDemoIT](../../../examples/simple-notes-demo/src/test/java/io/github/ande1922/moduvera/example/notes/NotesDemoIT.java)
- [Notes README](../../../examples/simple-notes-demo/README.md)
- [Notes POM](../../../examples/simple-notes-demo/pom.xml)
- `./mvnw -pl examples/simple-notes-demo -am verify`；使用 01 外置 Agent 与接收端、真实 PostgreSQL/Kafka，另外核对独立 Parent/BOM 的实际消费启动及未使用运输的最小依赖消费者。底层 CAS/故障完整矩阵复用 10–13 的准确证据，不在 Notes 重写一套。

## Exclusions

不新增第二个业务产品，不扩大 Notes 支持范围、不替换其既定旧 demo 数据库升级政策；不自动提升 Product Surface/finding 状态，不因测试存在宣称最终交付 PASS。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。
