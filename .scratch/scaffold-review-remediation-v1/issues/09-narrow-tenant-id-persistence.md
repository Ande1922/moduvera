Type: issue
Status: resolved
Blocked by: 08

# 09 — 安全收敛 Tenant ID 持久化契约

## Outcome

把 Reference Product 及可靠消息持久化中的 tenant 表示统一为 `VARCHAR(64)`，并通过前向迁移在发现不兼容历史数据时显式失败，避免静默截断或隐式类型转换。

## Acceptance Criteria

- [x] Catalog、Order、Inventory、Identity、Outbox/Inbox、示例及产品验证资产中的所有 tenant 列与 canonical 1–64 字符契约一致。
- [x] 受影响的 PostgreSQL 与 MySQL schema 通过新增的兼容前向迁移收窄；已发布的历史 V1 migration 不被重写，最新迁移先检测超过 64 字符的历史数据并以明确错误失败，不截断、不重写租户含义。
- [x] 现有索引、唯一约束、租户隔离和查询语义保持成立，真实数据库迁移测试覆盖兼容数据与超长数据两条路径。
- [x] MyBatis tenant line 和其他持久化边界继续使用字符串 SQL 值；Kernel、Data Starter 和服务代码不新增 String/BIGINT 转换 SPI，也不依赖数据库隐式转换。
- [x] 新建数据库完成全部 migration 后，产品 schema 与 verification 资产的有效 tenant 列均为 `VARCHAR(64)`；历史 migration 中保留的旧定义仅作为可升级输入，不被误报为当前契约。

## Verification

- 对 PostgreSQL 与 MySQL 运行前向迁移和现有隔离测试。
- 先迁移到历史 V1、插入 65 字符 tenant，再升级到最新版本，验证迁移在改变列定义前失败且数据不变。
- 扫描所有产品与验证 DDL，确认 tenant 列长度和字符串表达一致。

## Out of Scope

- 正式业务系统的 BIGINT 主键策略、租户发号器、目录或通用转换层。

## Answer

- 实现分支：`codex/remediation-v1-09-tenant-persistence`，最终提交 `542fdc5a30966066d38a9f5f6fa37a6edfc5cc49`。
- 最终 Standards Review 与 Spec Review 均为 no findings；历史 V1 相对固定 base 无差异，新增 V2 在改列前拒绝 65 字符历史数据并保留数据与原列宽。
- Ticket 分支最终验证：Messaging migration 2/2、MySQL repositories 4/4、Catalog App 8/8、Identity migration 1/1、schema gate 4/4，全部通过。
- Monolith migration evidence 已同步精确验证 Catalog 与 Messaging 的 V1+V2，真实依赖整类 `ModuveraMonolithApplicationIT` 通过；该回补的最终 Standards/Spec Review 均为 no findings。
- 集成态 Ticket 09/10 共享 MySQL 表面再次验证 4/4，schema gate 4/4，Reactor BUILD SUCCESS；schema scanner 覆盖 CREATE 顶层列、PostgreSQL TYPE/SET DATA TYPE、ADD/MODIFY 与 MySQL CHANGE，同时避免把约束和非类型 ALTER 误报为列声明。
