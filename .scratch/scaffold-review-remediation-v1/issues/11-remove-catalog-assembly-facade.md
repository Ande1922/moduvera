Type: issue
Status: resolved
Blocked by: None

# 11 — 删除 Catalog 废弃装配门面

## Outcome

移除无生产消费者的 Catalog service-level deprecated configuration facade 及只为维持它存在的测试约束，使受支持装配入口唯一且不改变现有业务模块层次。

## Acceptance Criteria

- [x] 废弃 configuration facade 被删除，仓库生产代码、测试和文档不再引用或发布该兼容入口。
- [x] 仅为该 facade 存在的兼容性测试、架构谓词和负向 fixture 被删除或收敛；其他 Catalog 架构保护不被削弱。
- [x] Runnable App 继续直接选择受支持的 Catalog 业务配置切片，并通过启动与架构测试。
- [x] `catalog.catalog` 业务模块的 package 层次与所有权保持不变，不因删除兼容门面而拍平或搬移公共契约。
- [x] 全仓扫描确认没有隐藏的生产、示例或验证消费者依赖被删除类型。

## Verification

- 运行 Catalog 聚焦测试、相关 App 启动测试和架构规则。
- 编译受影响 Reactor 范围并扫描已删除 facade 的引用。

## Out of Scope

- 重组 Catalog 业务模块、改变其公开 API 或重新设计 App Assembly。

## Answer

- 实现分支：`codex/remediation-v1-11-catalog-facade`，最终提交 `b00f142a9e27400bc2a3a3f46f33f04588dbe6f3`。
- 最终 Standards Review 与 Spec Review 均为 no findings；deprecated service-root facade、专属兼容测试、ArchUnit predicate 与 negative fixture 已删除，通用所有权保护保留。
- Catalog service 7/7、Assembly ArchUnit 23/23、Current scaffold 17/17、真实 PostgreSQL `CatalogApplicationIT` 8/8 通过；受影响 Reactor install 与 deleted-FQN/JAR scan 通过。
- Catalog App 与 monolith 继续直接组合 module/API、persistence、HTTP inbound、migration 四个受支持 slice，`catalog.catalog` package/API/App Assembly 未改变。
