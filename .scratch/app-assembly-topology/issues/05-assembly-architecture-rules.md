# 05 — 锁定 App Assembly 与 Inbound Adapter 所有权

**What to build:** 用仓库架构测试把业务服务拥有 Inbound Adapter、App 只负责装配、Gateway 只负责 Route/Filter，以及 Service API 和业务核心协议中立的规则变成可执行约束。

**Blocked by:** 01 — 显式装配 Catalog Local API、Persistence 与 Internal HTTP; 02 — 让 Order HTTP 与库存结果入站行为可复用; 03 — 让 Inventory 预留命令处理可复用; 04 — 用服务名 Route/Filter 替换 Gateway 业务代理.

**Status:** resolved

- [x] 架构测试禁止 Gateway App 声明业务 `@RestController`、业务 `@RequestMapping` 或响应代理。
- [x] 架构测试禁止叶子 App 声明业务 HTTP/消息映射、Inbound Message Contract、payload mapping 或业务 handler。
- [x] 架构测试保证 Controller/Consumer 位于提供方 Service 的明确 Inbound Adapter package。
- [x] `*-api` 不依赖 Web/Gateway/传输 DTO，Domain/Application 不依赖 Spring Web、Messaging、Cloud Stream、Kafka 或可靠消费实现。

## Answer

由 `ea72d7d` 实现并由 `56ed028` 修正。Architecture Testkit 已验证 Gateway、
叶子 App、提供方 Inbound Adapter 与协议中立 API/Application 的所有权边界，
当前结构测试继续作为回归门禁。
