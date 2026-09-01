# 01 — 拆分 Migration Definition 与执行选项

**What to build:** 为数据库迁移建立无副作用的 Definition、装配拥有的执行选项和 Framework 组合出的可执行 Plan，使业务服务可以声明自己拥有的组件与数据库方言资源，同时不破坏现有迁移调用者。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Migration Definition 只描述组件身份、占位符及 PostgreSQL/MySQL 资源；构造或读取 Definition 不获取 DataSource、不连接数据库也不执行 SQL。
- [x] 执行选项与 Definition 分离，并可针对明确数据库方言组合成现有可执行 Migration Plan。
- [x] 已发布的 Migration Plan 形状和既有构造方式保持兼容；现有调用者无需在本票中迁移。
- [x] 缺失方言资源、重复或无效定义等非法输入 fail-fast，并由聚焦测试覆盖。

## Answer

Implemented by `cebfae426e1768b28a333e93b735a93617ca65b2` with review fix `6bed0efb3a88c57745be5d63e23e3ade676ebcf1` and integrated into `codex/business-layout-integration`.

- Standards review: passed after duplicate-location fail-fast validation was added for both dialects.
- Spec review: passed; the existing public Migration Plan shape remains unchanged and runtime selection stays deferred to Ticket 02.
- Verification: `./mvnw -pl framework/foundation/moduvera-database-migration -am verify` passed with 8 unit tests, 2 Testcontainers integration tests, Spotless, PMD and JaCoCo.
