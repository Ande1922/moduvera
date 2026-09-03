# 13 — 建立 MySQL 长期兼容 TCK

**What to build:** 使用同一组 Repository、tenant 与 migration contract 在真实 MySQL 上资格验证 Catalog、Order、Inventory 和消息可靠性表，使未来数据库切换具有可量化适配成本，同时保持 PostgreSQL 为唯一黄金路径。

**Blocked by:** 08 — 闭合 Order 与 Inventory 异步履约.

**Status:** resolved

- [x] Catalog、Order 和 Inventory 的 Repository TCK 在真实 MySQL 上覆盖 create、rehydrate、query、update、version conflict 与 transaction rollback。
- [x] tenant TCK 覆盖 context-derived insert、select/update/delete 隔离、missing-context fail closed、复合唯一键和外键。
- [x] Outbox/Inbox schema 与 store contract 在 MySQL 覆盖 append、claim/lease、published/failed、dedupe、并发重复和同事务行为。
- [x] 每个服务拥有 MySQL dialect migration，并通过 component identity、initialize/migrate/validate 与私有 history 规则。
- [x] 允许 MySQL/PostgreSQL 专属 SQL 和 migration 留在 persistence boundary；不引入 weakest-common-SQL DSL 或第二 ORM。
- [x] MySQL 测试不启动 Gateway/Kafka/完整订单 E2E，不要求 PostgreSQL-native AI/pgvector 能力对等。
- [x] 文档明确 PostgreSQL 继续拥有默认配置、端到端、性能基线和故障恢复证据，MySQL 状态仅来自该兼容 TCK。

## Answer

由 `71d3f37` 完成。Catalog、Order、Inventory 和 JDBC Messaging 的 MySQL
runtime-adapter TCK 覆盖租户隔离、迁移、事务、claim/lease、fencing、
cleanup 与 redrive；PostgreSQL 仍是唯一黄金路径和端到端验证环境。
