# 09 — 完成 foundation hardening 仓库级验收

**What to build:** 把所有已审查切片按依赖顺序集成为一个可发布验证的运行时硬化结果，并从公开产品缝隙证明安全默认值、远程协作和能力撤回能够共同成立。

**Blocked by:** 01 — 新增数据库迁移 VALIDATE 运行模式; 02 — Identity App 改用 Service-owned Migration Definition; 03 — 所有持久化参考拓扑默认采用 validate-only; 04 — 由 Web Starter 统一映射应用层 Permission Denial; 05 — 让 Notes 独立消费者验证 Migration 与 Web Starter 契约; 06 — Identity Token 调用迁移到 declarative Client Group; 07 — 完成 Order 到 Catalog 的 declarative Client Group 链路; 08 — 撤回 Object Storage 并冻结无消费者能力.

**Status:** resolved

- [x] 当前 full-reactor `clean verify` 通过，包含格式、静态分析、架构、单元、集成、JaCoCo 与 BOM smoke 检查。
- [x] Reference-product harness 在 microservices 与 business-core-monolith 两种拓扑均通过，并显式提供 Worker ID 与 disposable-database 初始化策略。
- [x] PostgreSQL Golden Path 以及现有 MySQL Migration/persistence 合约继续通过，不增加无依据的全拓扑 MySQL 矩阵。
- [x] 仓库检查证明没有当前代码或产品文档继续发布 Object Storage，Lock/Scheduler 既有测试保持不变。
- [x] 集成结果只包含本规格和已确认前置成果，未吸收、覆盖或丢弃原工作区的无关修改。
- [x] 每个前置 ticket 的验收证据、独立双轴审查和集成状态均可追溯。

## Answer

- 验收基线：`5077ed6`，其祖先包含用户工作区 checkpoint `cfcb52c8` 以及 Tickets 01–08 的已复核实现、修正、合并和追踪提交。
- Docker 可见环境中的 `./mvnw clean verify` 通过全部 30 个 reactor 模块（2 分 55 秒），覆盖 Spotless、PMD、单元/集成测试、JaCoCo、架构检查、BOM smoke，以及 PostgreSQL/MySQL Migration 合约。
- `REFERENCE_SKIP_BUILD=1 verification/reference-product/harness/verify.sh microservices` 与 `business-core-monolith` 均通过公开 HTTP 验收和 Kafka 恢复两阶段；harness 显式传入 Worker ID 与 `STARTUP + initialize=true`。
- 静态检查确认 Object Storage 模块目录已撤回，当前代码、BOM 和产品文档不再发布该 API；Lock/Scheduler 代码与测试相对 checkpoint 字节不变。
- Standards 终审唯一发现是补齐本 Answer 与 resolved 状态；Spec 终审无发现。主工作树与集成 worktree 均保持干净，未 push、deploy、重写历史或删除 worktree。
