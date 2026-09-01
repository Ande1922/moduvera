# 01 — 发布 Inventory 拥有的规范消息身份

**What to build:** 让 Inventory 的 Reserve Inventory Command 与库存预占结果契约发布框架无关的规范 Message Kind、Message Type 和 Destination，使消息两侧使用同一提供方拥有的身份，同时不改变现有 wire contract。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Inventory API 模块发布 Reserve Inventory Command 与库存预占结果的规范 kind、type 和 logical destination，且不依赖 Spring、Kafka、消息 starter 或序列化框架。
- [x] Order Publisher、Inventory Consumer、Inventory Result Publisher 与 Order Consumer 使用提供方发布的规范值，不再各自拥有相同消息身份字面量。
- [x] 当前 v1 Message Type、logical Destination 与 Payload 字段保持不变；source、consumer ID、Execution Actor 和 permissions 仍由其所属的生产或消费策略拥有。
- [x] Inventory API、Order Service 与 Inventory Service 的聚焦测试通过，并覆盖规范值不会漂移。

## Answer

Implemented by `39ea77a` and `7ad3dbe`. Inventory now publishes framework-neutral canonical identities for its command and result contracts, all four producer/consumer adapters reuse them, and independent service-side behavioral tests guard against adapter drift. Focused unit, Spotless, PMD, and packaging verification passed; Standards and Spec reviews both passed.
