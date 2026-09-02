# 02 — Identity App 改用 Service-owned Migration Definition

**What to build:** 让 Identity 以与其他持久化 Business Service 相同的无副作用 Migration Definition 和 Migration Starter 参与迁移策略，同时保持现有 Demo 身份能力不变。

**Blocked by:** 01 — 新增数据库迁移 VALIDATE 运行模式.

**Status:** resolved

- [x] Identity 发布固定现有组件身份、迁移位置和 Flyway 历史身份的无副作用 Migration Definition。
- [x] Identity App 不再直接构造或调用数据库迁移执行器，正常配置选择 `VALIDATE`。
- [x] 对已迁移数据库的正常启动成功，未初始化或不兼容数据库在启动阶段失败。
- [x] Identity 集成测试显式选择 disposable database 初始化，并继续覆盖登录、交换、Service Token 与 JWKS 行为。
- [x] 临时内存签名密钥继续仅作为 Demo 证据，不新增生产密钥持久化、轮换或多实例能力。

## Answer

Implemented by commit `5fe3d0b`, independently reviewed on standards and specification axes, and integrated into `codex/foundation-hardening-integration`. Identity now publishes a PostgreSQL-only side-effect-free definition and consumes the Migration Starter; its seven integration tests cover migrated, uninitialized and incompatible startup plus existing login, token and JWKS behavior. Reference-harness initialization remains intentionally assigned to downstream Ticket 03.
