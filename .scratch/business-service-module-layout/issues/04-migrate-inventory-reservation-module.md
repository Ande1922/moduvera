# 04 — 迁移 Inventory 预留模块与 Inventory App

**What to build:** 将 Inventory 预留能力组织为业务模块，并通过公开 Inbound Configuration、package-private Message Consumer、方向明确的 Outbound Adapters 和显式 Inventory App 装配保持可靠消费、原子预留与结果发布语义。

**Blocked by:** 02 — 提供通用启动迁移运行时与消息平台定义; External — 完成并集成“库存异步契约交接”; External — 完成并集成“移除不完整的 InMemory Adapter”规格.

**Status:** claimed

- [ ] Inventory Module Configuration 只构造协议中立的 Application Service，不激活消息、Persistence、Migration 或运行环境实现。
- [ ] 消息入口由公开 Inbound Configuration 固定 Consumer Bean 和契约，package-private Consumer 承担可靠消费后的控制流；不重新引入同步 Inventory Service API 或无语义 Mapper。
- [ ] MyBatis Persistence 与结果发布实现位于对应 Outbound Adapter，服务 Migration Definition 无副作用，Persistence 配置不执行迁移。
- [ ] Inventory App 显式选择业务 Module、消息 Inbound、Persistence/Publication Adapters、服务与消息平台 Definitions，并保持 malformed Payload、Inbox/Outbox、可信上下文及 Reserved/Rejected 行为。
