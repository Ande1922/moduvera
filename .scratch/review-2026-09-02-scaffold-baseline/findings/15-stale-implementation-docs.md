Type: finding
Status: confirmed
Severity: minor
Area: docs
Claim: DATA-MODEL-CONFIRMATION 仍声称模型尚未进入 SQL/Repository 实现，与当前三服务 migration 和生产 Repository 矛盾。
Evidence: DATA-MODEL-CONFIRMATION 的状态说明已经陈旧。认证设计明确把未完成范围标为 Proposed；ENTITY-AUDIT-DEMO 也明确标为 historical/superseded 并指向 order/design，它们不支持原先更宽的“多份文档误导”结论。
Verification: 读取 DATA-MODEL-CONFIRMATION 的状态声明，并与 Catalog、Order、Inventory migration 和生产 Repository 对照；同时确认认证设计与 ENTITY-AUDIT-DEMO 已经清晰限定适用范围。
Fixed: 0981e212fc22b86ba65a0df12fe26158482be759

## Verdict

确认。`docs/implementation/DATA-MODEL-CONFIRMATION.md:3,7,95` 仍把四套 schema、migration 与 Repository 描述为确认后的未来工作；当前仓库已经存在 Catalog、Order、Inventory 的 PostgreSQL/MySQL migration，以及 `MybatisCatalogProductRepository`、`MybatisOrderRepository` 和 `MybatisInventoryStore` 生产实现。该文档的建议模型也不是当前 schema 的可靠 as-built 描述，例如建议 `sales_order` 和规范化的 reservation line，而当前实现使用 `order_header` 与 `inventory_reservation_result.unavailable_product_ids`。除 `docs/grill/OPEN-QUESTIONS.md:30` 的另一条陈旧 pending 描述外没有当前引用。认证技术设计明确标为 Proposed 且“不代表已经完成实现”，ENTITY-AUDIT-DEMO 明确标为 Historical/superseded，因此本 finding 只确认 DATA-MODEL-CONFIRMATION 及其陈旧引用，不扩大到其他实施文档。

Disposition: 接受。提交 `0981e212fc22b86ba65a0df12fe26158482be759` 已删除失去 as-built 权威性的 `DATA-MODEL-CONFIRMATION.md` 并清理陈旧引用；`OPEN-QUESTIONS.md` 只保留当前 primitive version 是否需要提升为 `AggregateVersion` 值对象这一尚待消费者证据的问题。

# 数据模型确认文档已经陈旧
