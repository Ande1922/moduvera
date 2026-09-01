# 02 — 提供通用启动迁移运行时与消息平台定义

**What to build:** 让 App 显式选择业务及平台 Migration Definitions，并由通用 Spring Boot 运行时根据 startup、external 或 disabled 策略确定是否组合并执行迁移；可靠消息平台发布自己的定义且每个数据库运行时只选择一次。

**Blocked by:** 01 — 拆分 Migration Definition 与执行选项.

**Status:** claimed

- [ ] 新的 Framework Starter 在显式 startup 模式下按确定顺序执行选中的 Definitions，并对重复组件身份 fail-fast。
- [ ] external、disabled 和默认未启用状态不获取 DataSource、不连接数据库也不执行 SQL。
- [ ] 可靠消息平台通过单独、显式选择的配置发布 PostgreSQL/MySQL Migration Definition；普通消息自动配置不会因 classpath 存在而自动选择它。
- [ ] Starter、消息平台定义、Reactor/BOM 暴露及配置行为拥有聚焦测试，且不迁移任何业务服务或 App 调用者。
