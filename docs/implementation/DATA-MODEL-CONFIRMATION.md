# 数据模型确认门

状态：**Proposed，尚未进入 SQL/Repository 实现**。

已经确认的边界不在这里重问：服务拥有自己的逻辑数据、`tenant_id` 可移植隔离、全局 Snowflake `BIGINT`、MyBatis-Plus 为唯一选定的持久化框架、PostgreSQL 为主路径且 MySQL 长期兼容、Flyway 服务内独立历史、应用用例边界授权、Outbox/Inbox 至少一次语义。

下面三项会直接决定公开数据模型和跨模块行为，按仓库工作约束需要一次整体确认后再生成四套 service schema 与 Repository TCK。

## 1. Identity 账号命名空间

推荐：**全局账号 + 多租户成员关系**。

```text
identity_account
  account_id, login_name, login_name_normalized, password_hash,
  status, authentication_version, created_at, updated_at

identity_tenant
  tenant_id, tenant_code, tenant_code_normalized, name, status,
  version, created_at/by, updated_at/by

identity_membership
  tenant_id, account_id, status, authorization_version,
  created_at/by, updated_at/by

identity_role
  tenant_id, role_id, role_code, name, status, version

identity_membership_role
  tenant_id, account_id, role_id

identity_role_permission
  tenant_id, role_id, permission_code
```

- `login_name_normalized` 全局唯一；一个账号可加入多个租户。
- 登录先识别账号，再选择/确认租户；Opaque Token 绑定当前租户。
- 普通角色必须属于租户；平台管理员和服务身份不伪装成租户用户。
- Spring Authorization Server 的 client/authorization/consent 表属于 Identity 服务，但与 RBAC 表分开。

待确认的替代方案：若账号应由每个租户独立拥有，则唯一键改为 `(tenant_id, login_name_normalized)`，登录请求必须先提供租户标识，同一个自然人跨租户会有多个账号。

## 2. 统一持久化字段

推荐：**明确复制必要字段，不引入通用持久化基类**。

- 所有租户聚合表：`tenant_id NOT NULL`、全局 `BIGINT id`、聚合级 `version BIGINT`。
- 审计列：`created_at`、`created_by_type`、`created_by_id`、`updated_at`、`updated_by_type`、`updated_by_id`；时间统一 UTC。
- 同服务重要关联同时包含 `tenant_id`，用 `(tenant_id, id)` 唯一键/外键阻止跨租户关联。
- 不增加全局 `deleted`/`deleted_at`；删除、归档、注销由各聚合显式建模。
- Java Domain 与数据库 Record 允许字段重复映射，不创建 `BaseEntity`、万能 Mapper 或通用 Repository。
- `tenant_id` 默认属于持久化隔离元数据。HTTP、消息或 Job 入口建立可信 Tenant Context，持久化 Adapter 从上下文自动写入并为查询、更新和删除增加租户条件；缺失上下文时失败关闭。
- 普通 Application、Domain 和 Repository 接口不为了技术隔离传递 `TenantId`。只有租户身份参与真实业务规则时，才在对应业务类型中显式建模。
- 跨租户查询、管理和 Job fan-out 使用独立且显式授权的接口，不提供通用的忽略租户开关。

## 3. Reference Business 最小 Schema

推荐如下，先证明租户、价格快照、库存幂等和异步结果，不扩展餐饮门店、菜单、支付等业务。

```text
catalog_product
  tenant_id, product_id, name, unit_price, currency_code,
  status, version, audit columns

inventory_stock
  tenant_id, product_id, available_quantity, version, audit columns

inventory_reservation
  tenant_id, command_id, order_id, outcome, completed_at

inventory_reservation_line
  tenant_id, command_id, product_id, requested_quantity, outcome

sales_order
  tenant_id, order_id, status, currency_code, total_amount,
  version, audit columns

sales_order_line
  tenant_id, order_id, product_id, product_name,
  unit_price, quantity, line_total
```

- 使用 `sales_order`，避开 SQL 保留字 `order`。
- Order 保存 Catalog 的名称/价格快照，不跨服务外键。
- Inventory 以 `(tenant_id, command_id)` 做业务幂等；结果行采用规范化表，不用方言相关 JSON。
- 每个有异步能力的服务拥有自己的 Outbox/Inbox 表和 Flyway history；不共享业务表。
- Query 模型先直接由同服务表投影，不提前增加读模型库。

## 一次性确认语句

如果没有修正，可直接确认：

> 数据模型按“全局账号 + 多租户成员、显式审计/版本字段且无通用软删、规范化的 Catalog/Order/Inventory 最小 Schema”整体执行。

确认后才进入：Identity/Catalog/Order/Inventory `*-schema`、独立 `*-migration`、MyBatis-Plus Repository adapters，以及 PostgreSQL/MySQL 共享 TCK。TCK 必须验证无上下文失败、自动写入租户、所有 DML 隔离以及跨租户不可区分于不存在；PostgreSQL 同时承担默认端到端和性能基线。
