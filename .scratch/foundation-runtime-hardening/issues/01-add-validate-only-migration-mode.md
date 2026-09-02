# 01 — 新增数据库迁移 VALIDATE 运行模式

**What to build:** 让应用能够在启动时只验证所选 Database Component 的身份与 Flyway 状态，不执行任何初始化或 DDL；显式启动迁移仍可为获准的 disposable database 完成初始化。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] `VALIDATE` 能作为 Migration Starter 的执行模式被选择，并拒绝任何初始化选项。
- [x] 已正确迁移的 Database Component 能通过验证，且验证前后 schema、组件身份和 Flyway 历史均没有写入。
- [x] 缺少组件身份、缺少必需的 Flyway 历史、无效历史或存在 pending migration 时，应用启动失败并给出可诊断原因。
- [x] 显式 `STARTUP + initialize=true` 仍可初始化当前已支持的 PostgreSQL 与 MySQL disposable database。
- [x] `EXTERNAL` 与 `DISABLED` 行为不扩展，也不新增嵌入式迁移 CLI。

## Answer

Implemented by commits `8706459` and `7c6bc0d`, independently reviewed on standards and specification axes, and integrated into `codex/foundation-hardening-integration`. The final foundation + Starter reactor verification passed all PostgreSQL/MySQL unit and integration tests, including atomic plan preflight, diagnostic precedence, and full user-schema no-write snapshots.
