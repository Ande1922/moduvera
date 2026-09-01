# 07 — 收缩旧形态并锁定模块与 Adapter 架构

**What to build:** 在所有 App 已切换后删除迁移期间保留的旧配置路径和 generic `configuration`/`infrastructure` 形态，并用 Architecture Testkit 与架构文档锁定业务模块、Adapter 方向、迁移定义/执行分离和 App Assembly 装配职责。

**Blocked by:** 06 — 用新配置切片重组业务核心模块化单体.

**Status:** resolved

- [x] 三个 Business Service 不再包含 generic top-level `configuration` 或 `infrastructure` package，只保留规格明确允许且新 App 不导入的 deprecated compatibility facade。
- [x] 所有消息入口迁移到绑定具体 `InboundMessageHandler` 的 `ReliableInboundEndpoint` 后，删除过渡期 deprecated、未绑定的 `ReliableMessageConsumer` 兼容入口，并证明生产装配不存在旧路径引用。
- [x] 三个业务叶子 App 与业务核心模块化单体不再直接构造迁移执行器或复制组件资源元数据；Identity 与示例调用者仅作为兼容消费者保留，除非编译迁移必需。
- [x] 架构规则验证业务模块归属、Inbound/Outbound Adapter 方向、具体消息 Handler 位于 `adapter.inbound.messaging` 并实现对应 Command/Event 分类接口、Application Service 不实现消息 Handler、Reliable 层拥有 Inbox/重试/可信上下文、Module Configuration 不激活 Adapter，以及 App Assembly 不拥有业务映射。
- [x] 负例 fixtures、当前仓库架构测试与模块地图/产品架构文档同步更新，同时保留 ADR 允许的 compatibility 例外。

## Answer

- Implementation: `b4b7792`, with review fixes `723d687` and `521517a`; integrated by `0e462661d476fdbe3f69087de3da59543efcfb2d`.
- Reviews: Standards PASS; Spec PASS. Final reviewers found no blocking issues and independently confirmed the type-based Handler guards, explicit Business Service ownership allowlist, isolated core/Adapter direction fixtures, and narrowed retry-mechanic boundary.
- Verification: coordinator ran `./mvnw -pl framework/testing/moduvera-architecture-testkit -am verify` PASS across 25/25 reactor modules. Architecture TestKit passed 43/43 tests; related application and Docker/Testcontainers integration tests, Spotless, PMD, and JaCoCo passed. The final assertion-isolation delta reran `-am test -DskipITs` PASS with the same 43/43 architecture result, and `git diff --check` passed.
