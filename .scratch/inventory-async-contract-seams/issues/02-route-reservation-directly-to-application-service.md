# 02 — 让库存预占消息直接调用 Application Service

**What to build:** 让 Inventory 消息 Inbound Adapter 在完成可靠消费、契约校验和 Payload 反序列化后直接调用 Inventory Application Service，删除没有受支持 Local Call 或 Remote Call 的同步 Java Service API 假接缝。

**Blocked by:** 01 — 发布 Inventory 拥有的规范消息身份

**Status:** ready-for-agent

- [ ] 同步形状的 Inventory Java Service API 被移除，Inventory Application Service 不再实现该接口，且授权、预占决策与结果发布行为保持不变。
- [ ] Reserve Inventory Command Handler 直接调用 Inventory Application Service，并复用现有协议无关 Command/Result；不增加镜像 DTO、Mapper 或测试专用公开接口。
- [ ] malformed Payload 仍被分类为不可重试且不会执行 Application 用例；kind、type、source 和 destination 仍在业务处理前校验。
- [ ] Application、Handler 与 configuration-slice 测试通过，并证明消息 inbound 可在没有公开 Inventory Java API 的情况下激活。
