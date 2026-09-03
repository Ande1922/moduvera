# 05 — 交付 Catalog PostgreSQL 与内部 HTTP Service API

**What to build:** 让 `catalog-app` 成为首个真实业务 App 装配：使用 PostgreSQL/MyBatis-Plus 保存租户拥有的产品与价格，并通过受认证的内部版本化 HTTP 契约返回下单所需的权威 Product Snapshot。

**Blocked by:** 01 — 固化独立消费者平台门禁.

**Status:** resolved

- [x] Catalog migration 创建 tenant-qualified product/price schema、约束、audit 与必要 version 字段，不使用 BaseEntity。
- [x] domain-specific Repository 和 Mapper 完成 create/rehydrate/query mapping；Domain/Application 不依赖 ORM、Row 或 Execution Context holder。
- [x] persistence adapter 从可信 context 获取 Tenant ID，missing context 在 SQL 前失败，tenant-a 无法读取 tenant-b 产品。
- [x] 内部版本化 HTTP adapter 实现现有 `CatalogApi` 语义并返回未包装 Product Snapshot。
- [x] endpoint 验证 internal JWT、service identity、audience、权限和 correlation；错误使用安全的 RFC 9457 Problem Details。
- [x] `catalog-app` 显式装配 Web、Resource Server、Data、migration 和 Catalog adapter，不依赖偶然 sibling package scan。
- [x] 真实 PostgreSQL integration 和 HTTP contract test 覆盖正常查询、未找到、跨租户、401、403、validation 与 migration validate。

## Answer

实现提交：`71d3f37`。

Catalog 的领域、持久化、HTTP 与 App Assembly 已形成真实纵向闭环。租户字段只存在于 `CatalogProductRow` 与 Mapper SQL，Repository adapter 从可信 `ExecutionContext` 获取租户；领域和应用接口不接收 `TenantId`。`catalog-app` 直接导入 `CatalogModuleConfiguration`（module/API）、`CatalogPersistenceOutboundConfiguration`（persistence）、`CatalogInternalHttpInboundConfiguration`（HTTP inbound）与 `CatalogMigrationConfiguration`（migration）四个受支持 slice；它们分别注册 Catalog API、Mapper/Repository、Controller/error mapping 和独立 Flyway history。

验收命令：

```bash
./mvnw -q -pl apps/catalog-app -am -DskipITs=false verify
```

在 JDK 26、PostgreSQL 18.6 Testcontainers 下通过。`CatalogApplicationIT` 以真实 HTTP 覆盖正常查询、tenant-a/tenant-b 隔离、404、401、403、路径校验 400、correlation 以及 Flyway validate/history。
