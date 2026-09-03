Type: review
Status: planned
Scope: scaffold architecture / agent workflow / verification-build-delivery / docs hygiene
Reviewer: agent session 2026-09-02
Baseline: 172d2ae

# 2026-09-02 脚手架基线系统评审

本文件及 findings 是非规范审计记录，不是能力状态源。当前脚手架能力承诺仍以 docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md 为唯一状态源。

## 总体判断

仓库的架构纪律较强：API 保持协议中立，租户上下文 fail closed，消息链路使用 Outbox/Inbox，双拓扑复用公共黑盒契约，并通过消费者证据决定能力 promotion。相较之下，交付与运维能力滞后；缺少托管 CI 使本地验证声明没有自动复核与独立执行产物，关键 agent 交付流水线也仍依赖个人环境而非仓库内契约。第三方仍可按 README 手工运行完整 Maven 与 harness 验证。

2026-09-03 已完成 disposition：第一迭代以 `.scratch/scaffold-review-remediation-v1/spec.md` 为范围契约，已完全定义的工作进入实施；ExecutionContext、可观测性、可靠消息入站职责、托管 CI、Mutation 和 Outbox 性能基线进入下一迭代或独立专题。

02、03、04、12 涉及产品面已经明确的 Demo、Planned、Incubating/Frozen 或 Deferred 边界。它们在独立验证时必须区分“可观察限制”与“违反当前产品承诺”，不能仅凭边界存在就确认缺陷。

## 已观察到的优点

- ArchUnit 规则配有负向 fixture，能证明规则会拦截预期违规。
- ExecutionContext 在缺少可信上下文时 fail closed，并覆盖线程清理与隔离。
- 产品面明确区分 Supported、Incubating/Frozen、Planned、Deferred、Withdrawn，并真实撤回了 Object Storage。
- 微服务与业务核心单体拓扑复用同一套公共 HTTP 验收契约。

## Findings

| ID | Severity | Area | Status | Claim |
| --- | --- | --- | --- | --- |
| [01](findings/01-no-ci.md) | major | delivery | confirmed | 无托管 CI，验证声明缺少自动复核与独立执行产物 |
| [02](findings/02-ephemeral-identity-rsa.md) | minor | apps | wontfix | Identity 重启会更换 RSA，使在途 JWT 失效 |
| [03](findings/03-no-governed-observability.md) | major | framework | confirmed | 业务上下文未进入日志/Trace，缺少受治理的分布式观测 |
| [04](findings/04-lock-scheduler-without-consumer.md) | minor | framework | wontfix | Lock/Scheduler 无业务消费者，Redisson 只有 BOM 坐标 |
| [05](findings/05-inventory-rules-in-store.md) | major | services | confirmed | Inventory 预占业务决策集中在持久化 Store 而非 Domain |
| [06](findings/06-inbound-transaction-ownership.md) | minor | services | confirmed | 消息入站事务所有权已有文档，但缺少可执行架构约束 |
| [07](findings/07-resolve-pending-stock-missing-transaction.md) | major | services | rejected | resolvePendingStock 没有事务 |
| [08](findings/08-threadlocal-context-with-webflux.md) | major | framework | confirmed | ExecutionContext 缺少覆盖 WebFlux、虚拟线程与异步执行的统一传播契约 |
| [09](findings/09-catalog-assembly-indirection.md) | minor | services | confirmed | Catalog 仍维护无生产消费者的废弃装配门面 |
| [10](findings/10-tenant-id-length-mismatch.md) | major | services | confirmed | Tenant ID 在签发方、消费方和数据库之间缺少统一契约 |
| [11](findings/11-brittle-reference-harness.md) | minor | delivery | confirmed | Reference Harness 缺少同宿主机并行实例的端口与 Kafka 健康隔离 |
| [12](findings/12-no-application-image-contract.md) | minor | delivery | confirmed | 缺少仓库拥有的应用容器镜像基线 |
| [13](findings/13-tracker-status-drift.md) | major | agent-workflow | confirmed | tracker 终态、完成清单与证据存在漂移 |
| [14](findings/14-agent-delivery-workflow-not-repository-owned.md) | major | agent-workflow | confirmed | 核心 agent 交付流程依赖个人 Skill，仓库无法独立恢复 |
| [15](findings/15-stale-implementation-docs.md) | minor | docs | confirmed | DATA-MODEL-CONFIRMATION 与现有实现矛盾 |
| [16](findings/16-no-business-service-scaffolding.md) | major | agent-workflow | confirmed | 缺少面向脚手架消费者的新业务服务配方或生成器 |
| [17](findings/17-inventory-message-trust-boundary.md) | minor | services | rejected | Inventory 没有 resource server，因此安全边界缺失 |
| [18](findings/18-blocked-outbox-baseline.md) | minor | delivery | confirmed | Outbox 性能基线 issue 在 blocker 已解决后仍为 blocked |
| [19](findings/19-reliable-inbound-role-ambiguity.md) | minor | framework | confirmed | Factory、Endpoint 与 InboxTemplate 的消息入站职责不够明确 |
