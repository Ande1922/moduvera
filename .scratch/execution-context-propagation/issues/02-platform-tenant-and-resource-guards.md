Type: issue
Status: claimed
Blocked by: 01

# Platform/Tenant 模型与租户资源保护

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US01、US02、US05、US14、US20、US21
Test seams: T01、T07、T08

## Outcome

显式表达 Platform 与 Tenant(id)，保持租户资源 fail-closed，证明可信临时切换与旧调用兼容；Platform 不是缺失、全局权限或 Job 的 GLOBAL 锁竞争范围。

## Base and scope

基于 01 已集成输出，使用其快照/Scope 验证三态切换。范围限核心模型、直接受影响消费者的必要兼容、租户数据边界、既有 Job/Lock 与对应领域文档；不依赖 HTTP 或消息新票。

## Acceptance criteria

- [ ] ExecutionContext 保持不可变整体，Scope 为 Platform 或合法非空 Tenant；Actor、Initiator、Correlation 的既有合法性保持。
- [ ] Holder.require 在 Platform/Tenant 成功，缺失失败；requireTenantId 与旧 tenantId 别名在 Platform 失败，不返回 null 或魔法租户。
- [ ] Platform↔Tenant、Tenant↔Tenant 及嵌套空状态切换后恢复全部原信息；无论同步成功或异常，子范围不回写父范围。
- [ ] 保留 Tenant 构造、initiatedBy、旧读取与 run/call/wrap 调用兼容；检查真实 record component、反射、序列化及二进制消费面，记录实际迁移而非宣称完全兼容。
- [ ] 通过生产租户持久化 Adapter 和真实数据库证明 Platform/缺失不能读写普通租户资源或退化为无过滤 SQL；拒绝写入无业务副作用，既有 Tenant 隔离仍成立。
- [ ] 租户专用的现有直接出站调用仍严格要求 Tenant，不因新模型变成无租户远程调用；无需修改的消费者也记录兼容依据。
- [ ] 既有 JobRunner/Lock 接缝保持 frozen：显式 context 的进入与退出正确，GLOBAL 锁范围不被转换成 Platform 授权，不新增调度/锁产品接口。
- [ ] 同步 CONTEXT.md、ADR 0003/0035 与传播 ADR 的已确认定义；保留普通业务接口租户透明、跨租户管理独立授权、无 IAM/经销商树建模的边界。

## Verification

- Kernel 公开模型/Holder/Snapshot 测试断言 Platform、Tenant、缺失三态以及完整 Actor/Initiator/Correlation；复用旧 Tenant 消费测试。
- [TenantLineHandler](../../../framework/starters/moduvera-data-mybatis-plus-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/data/mybatis/ExecutionContextTenantLineHandler.java) 单测不能替代真实 SQL；复用既有 PostgreSQL 消费者与 MySQL Repository 集成接缝，按 ADR 0034 验证拒绝与状态未改变。
- 对既有私有 HTTP client、Job/Lock 运行最小相关测试；检查模块编译与框架无关依赖。先 Kernel 聚焦测试，再按当前实际测试位置记录 Maven verify/IT 命令；不能默认跳过 IT。
- 本票可在 06/10 不存在时完成；不把平台 HTTP 或平台消息能力作为验收前提。

## Exclusions

不放宽 JWT 规则、不加 HTTP 入口、不扩消息 wire，不实现授权审批/模拟身份、不把平台身份注入普通租户数据接口，不扩展 Job/Lock 产品。

## Comments

- 2026-09-05：等待 01 的恢复契约；本票覆盖 US20 的 Job/Lock 部分，消息由 10 承担。
