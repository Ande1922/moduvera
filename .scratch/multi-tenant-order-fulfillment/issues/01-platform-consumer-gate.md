# 01 — 固化独立消费者平台门禁

**What to build:** 让一个不继承脚手架 parent 的独立消费者仅导入平台 BOM、Web、OAuth2 Resource Server、Data 与 Migration 组件，就能通过真实 HTTP 和 PostgreSQL 安全地完成租户隔离记录的创建与查询。该消费者同时成为后续业务 App 使用公共平台能力的可执行基线。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] 独立消费者只导入 BOM，所有 platform artifact 均不声明版本，并验证 JDK、Boot 与 MyBatis-Plus 版本收敛。
- [x] Web 与 OAuth2 Resource Server 被显式、独立引入；成功响应、validation、RFC 9457、401、403 和 correlation 行为通过真实 HTTP 验证。
- [x] 使用真实 PostgreSQL 和显式 migration 验证 persistence、transaction、tenant-a/tenant-b 隔离及 missing-context fail closed。
- [x] 缺少唯一 `PlatformTransactionManager` 时 Data Starter 明确启动失败，不能静默缺少 `TransactionBoundary` 或 TenantLine。
- [x] 消费者添加自定义 MyBatis-Plus InnerInterceptor 后平台 TenantLine 仍被装配并通过隔离测试。
- [x] 请求结束和异常路径均清理 Execution Context，后续请求不能看到前一请求的租户或身份。
- [x] 现有 Notes consumer 可被深化复用；不得再创建一个语义重复的演示模块。

## Answer

独立 Notes consumer 现在自动识别 OrbStack Testcontainers 环境，并在自定义 MyBatis-Plus InnerInterceptor 存在时仍由 Data Starter 把 TenantLine 安装在首位。真实 PostgreSQL、HTTP、认证授权、RFC 9457、correlation、跨租户 404、transaction bean 和 Data Starter fail-fast 均已验证。聚焦 Starter 测试与独立 consumer `verify` 在 JDK 26 下通过。
