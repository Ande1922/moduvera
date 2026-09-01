# 03 — 迁移 Catalog 参考业务模块与 Catalog App

**What to build:** 将 Catalog 作为首个完整参考业务模块，使用小型 Module Configuration、方向明确的 Inbound/Outbound Adapters、服务拥有的 Migration Definition 和显式 Catalog App 装配，同时保持商品查询、鉴权、租户隔离与 HTTP 契约不变。

**Blocked by:** 02 — 提供通用启动迁移运行时与消息平台定义; External — 完成并集成“移除不完整的 InMemory Adapter”规格.

**Status:** ready-for-agent

- [ ] Catalog Module Configuration 只构造协议中立的 Application Service，不激活 HTTP、Persistence、Migration 或运行环境实现。
- [ ] Internal HTTP 位于 Inbound Adapter，MyBatis Persistence 位于 Outbound Adapter；Persistence 配置不再执行迁移。
- [ ] Catalog 发布无副作用的 PostgreSQL/MySQL Migration Definition，独立 Catalog App 显式选择 Module、Adapters、Definition 与 startup 策略。
- [ ] 配置切片、Catalog App 集成及既有业务测试证明未选择的 Adapter 不出现，公共行为保持兼容；规格允许的 deprecated facade 继续可用但不被新 App 导入。

