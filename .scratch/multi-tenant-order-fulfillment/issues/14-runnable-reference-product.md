# 14 — 交付一条命令可体验的完整成品

**What to build:** 提供一个面向业务开发者、人工维护者和 AI 编码代理的完整参考成品：通过清晰命令启动 PostgreSQL、Kafka、Gateway、Identity、Catalog、Order 和 Inventory，并运行只依赖公共 HTTP 的黑盒订单履约验收。

**Blocked by:** 11 — 证明 Outbox/Inbox 故障恢复能力; 12 — 证明并发不超卖与跨线程租户隔离; 13 — 建立 MySQL 长期兼容 TCK.

**Status:** resolved

- [x] 脚手架 reactor 的 verify/install 在固定 JDK、Maven、Boot、Cloud、MyBatis-Plus 组合下通过。
- [x] 开发/验收 harness 用一条明确命令启动真实依赖和五个 App 装配，且不被描述为生产部署参考。
- [x] 独立黑盒 suite 从公共登录开始，通过 Gateway 创建并查询库存充足与不足订单，使用隔离数据库/topic/group/Run ID 和 bounded Eventually assertions。
- [x] 黑盒 suite 同时执行认证错误、权限、跨租户、并发不超卖、虚拟线程/线程池隔离及关键故障恢复场景。
- [x] 超时失败输出可行动的 App、容器、correlation、message、consumer group 和 DLQ 诊断，不打印 token 或敏感 payload。
- [x] README 展示最短使用路径、业务模块标准位置、Starter 直接依赖、Service API/adapter/App Assembly 关系及常见失败模式。
- [x] supported surface 只有一个当前状态来源，并按 consumer evidence 更新 supported、incubating、planned 和 deferred；BOM 管理坐标不等于能力启用。
- [x] 架构、dependency convergence、production/test-support 隔离和“新增 Maven 模块必须有真实 consumer”门禁全部通过。
- [x] 新业务开发者或 AI 能从参考成品实现相邻用例，而无需在业务代码中理解 TenantLine、JWT claim、Kafka header、Flyway history、Outbox lease 或 MyBatis-Plus Wrapper。

## Answer

由 `71d3f37` 交付可运行参考产品、公共 HTTP 黑盒验收和支持范围文档，随后
`ea72d7d` 补齐共享双拓扑验证。验证 harness 使用真实 PostgreSQL/Kafka、
隔离 Run ID 和有界 Eventually 断言，并提供不泄露凭据的失败诊断。
