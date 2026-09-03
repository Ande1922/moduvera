Type: issue
Status: ready-for-agent
Blocked by: None

# 11 — 删除 Catalog 废弃装配门面

## Outcome

移除无生产消费者的 Catalog service-level deprecated configuration facade 及只为维持它存在的测试约束，使受支持装配入口唯一且不改变现有业务模块层次。

## Acceptance Criteria

- [ ] 废弃 configuration facade 被删除，仓库生产代码、测试和文档不再引用或发布该兼容入口。
- [ ] 仅为该 facade 存在的兼容性测试、架构谓词和负向 fixture 被删除或收敛；其他 Catalog 架构保护不被削弱。
- [ ] Runnable App 继续直接选择受支持的 Catalog 业务配置切片，并通过启动与架构测试。
- [ ] `catalog.catalog` 业务模块的 package 层次与所有权保持不变，不因删除兼容门面而拍平或搬移公共契约。
- [ ] 全仓扫描确认没有隐藏的生产、示例或验证消费者依赖被删除类型。

## Verification

- 运行 Catalog 聚焦测试、相关 App 启动测试和架构规则。
- 编译受影响 Reactor 范围并扫描已删除 facade 的引用。

## Out of Scope

- 重组 Catalog 业务模块、改变其公开 API 或重新设计 App Assembly。
