# 07 — 交付 Inventory 幂等库存预留

**What to build:** 让 `inventory-app` 从真实 Kafka 消费 `ReserveInventoryCommand`，在 PostgreSQL 中原子完成多行库存预留，并通过 Durable Outbox 发布 `InventoryReserved` 或 `InventoryRejected`。

**Blocked by:** 04 — 跑通 Kafka 到 PostgreSQL Inbox 的幂等消费链路.

**Status:** resolved

- [x] Inventory migration 创建 tenant-qualified stock、reservation/result、Inbox 和 Outbox schema 及必要唯一约束。
- [x] 多行命令按稳定 product ID 顺序处理，并通过条件更新、version 或行锁保证 `available >= requested`。
- [x] 任一订单行库存不足时所有库存变化回滚，并持久化唯一的 rejected result；不产生部分预留状态。
- [x] 相同 command ID 重复或并发投递返回既有结果，不重复扣减库存或重复生成业务结果。
- [x] Inbox、库存变化、reservation result 和 Event Outbox 在同一 `TransactionBoundary` 中提交或回滚。
- [x] `InventoryReserved`/`InventoryRejected` 由 Inventory Service API 拥有，并以 tenant-plus-order key 发布到真实 Kafka。
- [x] PostgreSQL integration test 覆盖成功、库存不足、多行回滚、重复命令、乐观冲突与基本并发不变量。

## Answer

实现提交：`71d3f37`。

`inventory-app` 使用显式 consumer bean 接收 `inventory.reserve`，由可靠消费层建立可信上下文并在一个 `TransactionBoundary` 内先写 Inbox marker，再执行稳定 product ID 排序的 `FOR UPDATE` 库存检查、条件/version 扣减、结果持久化与 Result Outbox。重复消息在 Inbox 处短路；Store 自身也能返回既有结果。库存不足路径在持锁检查后不执行任何扣减，因此不会出现部分预留。

验收命令：

```bash
./mvnw -q -pl apps/inventory-app -DskipITs=false verify
```

在真实 Kafka 与 PostgreSQL Testcontainers 下通过。覆盖 broker 投递、成功多行扣减、同消息重复投递、跨租户库存不受影响、不足时全行不变、唯一 rejected result、Inbox/stock/result/Outbox 同事务回滚、stale version 条件更新失败，以及 tenant-plus-order partition key。
