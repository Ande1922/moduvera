# 03 — 跑通 PostgreSQL Outbox 到 Kafka 的发布链路

**What to build:** 交付 `platform-messaging-kafka-spring-boot-starter` 的首个完整发布 tracer：独立消费者在本地事务中同时保存业务状态与 Outbox，事务提交后由 relay 通过 Spring Cloud Stream 和真实 Kafka 发布消息。

**Blocked by:** 01 — 固化独立消费者平台门禁; 02 — 收敛 Broker-neutral Messaging Core.

**Status:** resolved

- [x] Starter 使用 Spring Cloud Stream imperative functional model 与 Kafka Binder，不向 Application Service 暴露 Kafka、Binder 或 `StreamBridge`。
- [x] 独立消费者在同一 `TransactionBoundary` 中提交业务记录与 durable Outbox；任一写入失败时两者同时回滚。
- [x] relay 只认领已提交记录，并通过真实 Kafka 发布完整 Message Envelope。
- [x] tenant、actor、original initiator、correlation 和 causation 从可信 Execution Context 写入 Envelope，业务 payload 不携带技术 Tenant ID。
- [x] partition key 可由 tenant 与业务聚合标识稳定计算，并能从 Kafka 观察到预期 key。
- [x] 缺少 binding、destination、serializer、Data/transaction dependency 或必要配置时 Starter 明确启动失败。
- [x] 独立 consumer test 证明 BOM versionless consumption、真实 PostgreSQL、真实 Kafka 和 Boot context 均成立。

## Answer

实现提交：`71d3f37`。

新增 Kafka Starter 的 fail-fast 自动装配、显式逻辑 destination 路由、PostgreSQL durable Outbox、claim/lease relay 和同步 broker ACK 配置。Event 以 structured CloudEvents JSON 发布，Async Command 使用独立 envelope，两个 schema 随 Message Core 发布；Kafka 传输使用原生 byte[] codec，稳定 partition key 可在真实 broker 中观察。独立 Notes consumer 证明业务写入与 Outbox 同提交/同回滚、业务 payload 不携带技术 tenant 字段，并以 versionless BOM 在 JDK 26、Boot 4.1.1、真实 PostgreSQL/Kafka 下通过 `clean verify`。
