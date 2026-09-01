# 02 — Identity App 改用 Service-owned Migration Definition

**What to build:** 让 Identity 以与其他持久化 Business Service 相同的无副作用 Migration Definition 和 Migration Starter 参与迁移策略，同时保持现有 Demo 身份能力不变。

**Blocked by:** 01 — 新增数据库迁移 VALIDATE 运行模式.

**Status:** ready-for-agent

- [ ] Identity 发布固定现有组件身份、迁移位置和 Flyway 历史身份的无副作用 Migration Definition。
- [ ] Identity App 不再直接构造或调用数据库迁移执行器，正常配置选择 `VALIDATE`。
- [ ] 对已迁移数据库的正常启动成功，未初始化或不兼容数据库在启动阶段失败。
- [ ] Identity 集成测试显式选择 disposable database 初始化，并继续覆盖登录、交换、Service Token 与 JWKS 行为。
- [ ] 临时内存签名密钥继续仅作为 Demo 证据，不新增生产密钥持久化、轮换或多实例能力。
