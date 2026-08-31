# 06 — 交付 Order 创建、查询与 Durable Outbox

**What to build:** 让 `order-app` 通过真实 Catalog HTTP client 获取权威价格，并在本地事务中创建可查询的 `PENDING_STOCK` Order 和 `ReserveInventoryCommand` Outbox，使订单即使暂时没有 Inventory 消费者也不会丢失履约意图。

**Blocked by:** 03 — 跑通 PostgreSQL Outbox 到 Kafka 的发布链路; 05 — 交付 Catalog PostgreSQL 与内部 HTTP Service API.

**Status:** resolved

- [x] Catalog HTTP client 实现同一 `CatalogApi`，处理 internal JWT/service identity、timeout、correlation 与 Problem Details mapping。
- [x] Order Application Service 在进入 `TransactionBoundary` 前完成所有 Catalog 远程调用；远程失败时不写 Order 或 Outbox。
- [x] Order migration 和 MyBatis-Plus adapter 持久化 order、order line、价格快照、状态、audit/version 与 tenant-qualified constraints。
- [x] `POST /api/v1/orders` 只接受 product ID 与正数量，返回 `201 Created`、`Location` 和 `PENDING_STOCK` Order View。
- [x] `GET /api/v1/orders/{orderId}` 返回未包装 Order View，JSON identifier 为 string，跨租户与不存在统一为 404。
- [x] Order 与 `ReserveInventoryCommand` Outbox 同提交同回滚，发布后的 Kafka payload 不包含技术 Tenant ID。
- [x] 架构测试禁止 Order Domain/Application 依赖 HTTP、ORM、Kafka、StreamBridge、Persistence Row 或 Execution Context holder。

## Answer

`order-app` 现在是显式装配的真实消费者。`CatalogHttpClient` 在事务前完成远程价格快照查询，并传递服务令牌、Tenant-Id、correlation 与有界超时；Order 的 PostgreSQL adapter 负责租户隔离、价格快照、audit 与 optimistic version。创建用例只在 Catalog 成功后打开本地事务，并在该事务内同时写 Order 和 Reserve Command Outbox。

验收命令：

```bash
./mvnw -q -pl apps/order-app -DskipITs=false verify
```

在 JDK 26、PostgreSQL 18.6 Testcontainers 下通过。真实 HTTP 测试覆盖 201/Location、字符串 ID、查询、401/403/400、跨租户 404、远端失败零写入，以及 Outbox append 后注入失败时 Order/Outbox 同时回滚；成功 Outbox 的业务 payload 断言不包含 Tenant ID。
