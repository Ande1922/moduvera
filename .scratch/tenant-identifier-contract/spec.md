Type: spec
Status: ready-for-agent
Fixes: review-2026-09-02-scaffold-baseline/10

# 统一脚手架的字符串 Tenant ID 契约

## Outcome

为 Tenant ID 建立覆盖身份签发、HTTP、消息、ExecutionContext 和 Reference Product 持久化的单一字符串契约，消除签发方接受而消费方拒绝以及 schema 长度漂移，同时不替正式业务系统决定 BIGINT 主键策略。

## Confirmed boundary

- Kernel 继续使用 `TenantId(String value)`，接受 1–64 个 portable identifier 字符；不改成 `long`、泛型或 `Object`。
- HTTP、JWT、消息和任务继续以字符串传输 Tenant ID，并使用 canonical `TenantId` 的同一值域。
- Identity 在创建 session 或签发用户/服务 JWT 前使用 canonical `TenantId` 校验，不再维护允许 128 字符的独立正则。
- Catalog、Order、Inventory、Identity、可靠消息 Outbox/Inbox、示例与产品验证资产中的 tenant 持久化表示统一为 `VARCHAR(64)`。
- 不在 Kernel、Data Starter 或其他脚手架模块增加 String/BIGINT 转换 SPI，也不依赖数据库隐式类型转换。
- 正式业务系统若复制脚手架源码，可在源头把 Tenant ID 调整为 BIGINT；若消费不可变框架制品，再由该业务系统另行决定是否需要显式边界转换。该工作不属于本规格。
- 对已存在 schema 的收窄迁移必须先发现超过 64 字符的不兼容数据并显式失败，不得静默截断或重新解释 Tenant ID。

## Implementation scope

- 修正 Identity login 与 service-token 入口的 Tenant ID 校验，并覆盖 64/65 字符边界和非法字符。
- 为 Catalog、Identity 以及 Messaging 的 PostgreSQL/MySQL schema 增加兼容的前向迁移，使 tenant 列收敛到 `VARCHAR(64)`。
- 检查所有主资源、示例和 verification DDL，保证产品资产不再出现与 canonical TenantId 冲突的 tenant 列长度。
- 保持 `ExecutionContextTenantLineHandler` 的字符串 SQL 表达；本版本不新增可替换的数值编码器。

## Out of scope

- 统一正式业务系统的业务表主键为 BIGINT。
- 设计租户发号器、租户目录、外部租户编码与内部数值主键映射。
- 为未来业务系统预先增加 Tenant ID 泛型、任意值容器或持久化转换 SPI。
- 改变 `TenantId` 的对外字符串序列化。

## Required evidence

- Identity 对非法 Tenant ID 拒绝签发，且签发的每个 tenant claim 都能被资源服务器和消息入口建立为 trusted ExecutionContext。
- HTTP、JWT、消息和任务入口使用同一组有效/无效 Tenant ID 契约测试。
- 所有产品数据库和产品验证资产的 tenant 列均使用 `VARCHAR(64)`，现有索引和隔离行为保持成立。
- 迁移在存在不兼容历史数据时显式失败，不发生静默截断或错误映射。
- MyBatis tenant line 继续产生字符串 SQL 值，且没有新增或依赖 String/BIGINT 隐式转换。
