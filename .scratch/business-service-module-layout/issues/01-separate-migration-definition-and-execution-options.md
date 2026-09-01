# 01 — 拆分 Migration Definition 与执行选项

**What to build:** 为数据库迁移建立无副作用的 Definition、装配拥有的执行选项和 Framework 组合出的可执行 Plan，使业务服务可以声明自己拥有的组件与数据库方言资源，同时不破坏现有迁移调用者。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Migration Definition 只描述组件身份、占位符及 PostgreSQL/MySQL 资源；构造或读取 Definition 不获取 DataSource、不连接数据库也不执行 SQL。
- [ ] 执行选项与 Definition 分离，并可针对明确数据库方言组合成现有可执行 Migration Plan。
- [ ] 已发布的 Migration Plan 形状和既有构造方式保持兼容；现有调用者无需在本票中迁移。
- [ ] 缺失方言资源、重复或无效定义等非法输入 fail-fast，并由聚焦测试覆盖。

