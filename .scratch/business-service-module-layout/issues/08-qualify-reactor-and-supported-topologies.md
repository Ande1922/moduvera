# 08 — 验证 Reactor 与两种受支持应用拓扑

**What to build:** 对集成后的完整变更运行仓库级质量门和同一套公共黑盒验收，证明五 App 微服务 Golden Path 与业务核心模块化单体在 package、装配和迁移重构后仍具有相同客户端可见行为。

**Blocked by:** 07 — 收缩旧形态并锁定模块与 Adapter 架构.

**Status:** claimed

- [x] 完整 Reactor 的编译、单元、集成、架构、格式化、PMD 和 JaCoCo 检查通过，并保留当前执行证据。
- [x] 微服务 Golden Path 与业务核心模块化单体均通过未放宽的公共 HTTP 黑盒 harness。
- [x] 验收覆盖订单创建/查询、库存确认/拒绝、Tenant 隔离、可信上下文、Outbox/Inbox、最终一致性与公共契约兼容。
- [x] 任何环境失败、未执行检查或支持矩阵限制被明确记录；仅在全部必需证据通过后将本 effort 标记完成。

## Qualification evidence

执行时间为 2026-09-01 至 2026-09-02（Asia/Shanghai）。执行环境为 macOS 26.5.2 arm64、OpenJDK 26（build 26+35-2893）、Maven 3.9.16、Docker Client/Server 29.4.0、Docker Compose v5.1.2 和 uv 0.11.24。

按顺序执行且未并发运行以下必需命令：

1. `./mvnw clean verify`：PASS。31/31 个 Reactor 模块成功，总耗时 04:06；编译、单元测试、集成测试、Architecture TestKit（43/43）、Spotless、PMD 和 JaCoCo 均通过。执行中实际启动了 PostgreSQL、MySQL 和 Kafka Testcontainers。
2. `verification/reference-product/harness/verify.sh microservices`：PASS。内部 `mvnw -q clean install` 及 `OrderApplicationIT`、`InventoryApplicationIT`、`ModuveraMonolithApplicationIT` 重复投递证据通过；共享公共 HTTP 测试为 `1 passed, 2 deselected`；Kafka 不可用时业务行与 Outbox 同事务提交、重启后 Outbox reclaim 并完成订单的恢复阶段 1/2 均通过。
3. `verification/reference-product/harness/verify.sh business-core-monolith`：PASS。与微服务拓扑执行相同的未修改 harness；内部全量构建和重复投递证据通过，共享公共 HTTP 测试为 `1 passed, 2 deselected`，Kafka 恢复阶段 1/2 均通过。

共享黑盒场景通过 Gateway 的公开 HTTP 路径验证了登录和鉴权、订单创建与查询、确认与拒绝两种库存结果、并发有界库存、跨 Tenant 不可见、RFC 9457 问题响应、原生成功响应、Correlation 传播及内部路由隔离。两种拓扑均通过异步轮询到终态和 Kafka 中断/恢复，提供最终一致性与 Outbox 恢复证据。全量 App 集成测试中的真实 Kafka 重复投递场景验证 Inbox 去重；Inventory 集成测试同时验证可信 Actor/Tenant/Initiator/Correlation 的传播和不可信生产者拒绝。

没有环境失败、未执行的必需检查或已知的支持矩阵限制。pytest 的 `2 deselected` 是 harness 使用 `-k public_order_fulfillment_reference_product` 只选择共享产品黑盒场景的预期结果；该 Python 模块另外两个本地契约单元测试未由这三个必需命令单独选择，它们不属于 Ticket 08 规定的拓扑验收矩阵，也不代表跳过任一拓扑验收。Ticket 状态保持 `claimed`，等待分支复核与集成后再标记完成。
