Type: finding
Status: confirmed
Severity: minor
Area: delivery
Claim: Outbox 性能基线 issue 16 仍为 blocked，但其唯一 blocker issue 15 已 resolved。
Evidence: .scratch/multi-tenant-order-fulfillment/issues/16-baseline-outbox-performance.md 声明 Blocked by: 15 且 Status: blocked；同目录 issue 15 为 Status: resolved。
Verification: 读取 issue 15 与 16 顶部字段和 Answer；按 docs/agents/issue-tracker.md 的解除条件确认 issue 16 是否应回到 intake 或 execution 的可工作状态。
Planned: .scratch/hosted-ci-quality-gates/spec.md

## Verdict

确认状态链不一致，但不能仅凭 blocker 的状态行直接解锁。`16-baseline-outbox-performance.md` 的唯一 blocker 是 15 且仍为 `blocked`；`15-harden-durable-publication-reliability.md` 标为 `resolved`，当前源码也存在 Relay 生命周期、数据库 claim token、lease/fencing、terminal/redrive、cleanup 及 PostgreSQL/MySQL 测试等对应实现。然而 15 没有 `docs/agents/issue-tracker.md` 要求的 `## Answer`，也未在 ticket 中记录最终提交与实际验证结果，因此其 resolved 状态本身需要由 Finding 13 的一次性 tracker 校准复核。校准确认 15 完成后，16 应解除 `blocked` 并回到适当 intake 状态；若证据不足，则先修正 15 的状态，而不是让无效 resolved 自动清除 blocker。

Disposition: 接受状态修复，但不把 16 号 Outbox 性能基线纳入下个版本实施。15 的完成证据校准通过后，16 恢复为 `ready-for-agent` 并保留为后续 ticket；等托管 CI/质量门禁提供稳定、可重复的数据库与 Kafka 运行环境后再排期，以免把宿主机噪声误当成产品基线。

# Outbox 性能基线错误保持 blocked
