# 02 — 提供通用启动迁移运行时与消息平台定义

**What to build:** 让 App 显式选择业务及平台 Migration Definitions，并由通用 Spring Boot 运行时根据 startup、external 或 disabled 策略确定是否组合并执行迁移；可靠消息平台发布自己的定义且每个数据库运行时只选择一次。

**Blocked by:** 01 — 拆分 Migration Definition 与执行选项.

**Status:** resolved

- [x] 新的 Framework Starter 在显式 startup 模式下按确定顺序执行选中的 Definitions，并对重复组件身份 fail-fast。
- [x] external、disabled 和默认未启用状态不获取 DataSource、不连接数据库也不执行 SQL。
- [x] 可靠消息平台通过单独、显式选择的配置发布 PostgreSQL/MySQL Migration Definition；普通消息自动配置不会因 classpath 存在而自动选择它。
- [x] Starter、消息平台定义、Reactor/BOM 暴露及配置行为拥有聚焦测试，且不迁移任何业务服务或 App 调用者。

## Answer

- 实现提交：`f508df08ed3dd9f68c94675563b5485a20913b87`。
- Standards review：PASS；Spec review：PASS，均无阻塞项。
- 协调端宿主 Docker 验证：
  - `./mvnw -pl framework/starters/moduvera-database-migration-spring-boot-starter,framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`：PASS，包含 PostgreSQL/MySQL 迁移运行时与消息 JDBC 集成测试、Spotless、PMD、JaCoCo。
  - `./mvnw -pl framework/testing/moduvera-bom-smoke -am test`：PASS。
- 已快进集成到本地 `codex/business-layout-integration`；未迁移业务服务或 App 调用者。
