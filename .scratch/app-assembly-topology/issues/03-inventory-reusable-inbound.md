# 03 — 让 Inventory 预留命令处理可复用

**What to build:** 让 Inventory 服务唯一拥有库存预留 Command 的契约校验、payload mapping 和用例调用；独立 Inventory App 只显式选择 Application、Persistence/Outbox 与消息 Inbound slices，并保持可靠消费、库存原子性与结果发布语义。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] 预留命令 Consumer 及其 Inbound Message Contract、反序列化和 handler 从叶子 App 移入 `inventory-service` 的明确 Inbound Adapter package。
- [x] Inventory Application、Persistence/Outbox 和消息 Inbound 可由独立配置入口选择，独立 App 通过显式导入组合它们。
- [x] 现有 Kafka、Inbox 去重、可信上下文、原子预留、结果 Outbox、回滚和重试 integration tests 继续通过。
- [x] App 配置不再声明业务 payload 转换、消息契约或业务 handler lambda。

## Answer

由 `ea72d7d` 实现。Inventory Application、Persistence/Outbox 与消息 Inbound
slices 已分离，处理器位于服务 Inbound Adapter；当前 configuration、handler
和真实 Kafka/PostgreSQL 测试验证幂等、原子预留、回滚与结果发布。
