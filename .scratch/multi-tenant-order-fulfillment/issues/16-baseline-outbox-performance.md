Status: blocked
Labels: performance, follow-up

# 16 — 建立 Outbox 发布性能基线并验证数据库瓶颈方向

**Blocked by:** 15 — 加固 Durable Publication 的低延迟、多实例与生命周期可靠性。

**What to build:** 在 15 号 issue 的 P0 正确性机制稳定后，以真实 PostgreSQL 和 Kafka 建立可重复的 Outbox 性能基线，找出 commit-to-Broker-ACK 延迟与吞吐的主导瓶颈，并只为有证据的问题提出优化。

## Required workloads

- 单实例稳态与突发积压。
- 多实例竞争、高基数 partition key 与热点 key。
- Kafka 正常、缓慢、不可用与恢复。
- 可控 GC 停顿、进程重启和滚动发布。

## Required measurements

- commit-to-Broker-ACK p50、p95、p99 与 messages/second。
- 每条消息数据库 statement 数、数据库 CPU/I/O/lock waits。
- oldest pending age、claim conflict/stale-token、重试与重复投递比例。

## Evidence-driven optimization directions

1. 先调整自适应 batch size、连续运行预算、poll fallback 与 send timeout/lease 的配置关系。
2. 若逐条状态更新成为主要瓶颈，再评估批量状态更新，并显式量化扩大重复窗口的代价。
3. 若数据规模与清理造成热点，再评估按 destination、时间或状态分区；不预先建设通用分区框架。
4. 若不同 key 的发送能力不足且数据库不是瓶颈，再增加受控 Relay 并发；同 key 顺序不变。
5. 只有 polling/claim/update 已被证明是结构性瓶颈时，才评估 CDC/Debezium 等替代触发链路。

## Completion evidence

- 基线脚本、环境参数和结果可重复，报告区分客户端、数据库与 Broker 时间。
- 每项建议引用测量结果，说明收益、重复窗口、复杂度和回滚方式。
- 未观察到的瓶颈不转化为实现任务。
