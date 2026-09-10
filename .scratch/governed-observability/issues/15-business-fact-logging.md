Type: issue
Status: ready-for-agent
Blocked by: 02

# 15 — 已有业务事实的记录责任与提交时机

## Outcome

现有参考业务用例只在事实成立时记录稳定业务 action，事务回滚没有成功事实，中间层不重复最终错误。

## Spec coverage

[正式 Spec](../spec.md)：US02（业务责任/事务事实）；ID01；TD01。依赖与全量故事映射见 [交付图](../map.md)。

## Base and dependencies

规划基线为 `ce1636f82f9652b2b14dd9e146e62c748fbf3233` 加已采用且未提交的 Spec/日志规范；它不是可直接实施的干净提交基线。实施前核对当前 HEAD、工作树及前置票的交付证据，并记录本票实际 base；保留其他未提交内容。直接前置：[02](./02-ecs-logging-and-context-projection.md)。必须取得前置能力在当前 checkout 可用的证据，不能只看其他分支的状态。

## Scope

在当前参考业务已有用例中落实规范打印决策，重点覆盖 Order/Inventory 的现有变更与事务；记录纳入范围的事实和责任方清单，保持业务状态机不变。稳定 event.action/error.code 登记遵循活规范，业务继续用 SLF4J，不引入通用日志门面或审计模块。

## Acceptance criteria

- [ ] 现有纳入范围的业务事实列出稳定 action、成立条件及唯一记录责任方；event.action 只表示已成立事实，通用异常不编造业务 action，纯查询不伪造状态变更。
- [ ] 依赖数据库提交的成功事实仅在真正提交后记录，append/save 返回不视为提交；外层回滚、提交失败无该成功事实，纯计算事实按实际成立点记录。
- [ ] 提交后记录保留应有 C/Trace 与可信身份快照，日志不延长授权作用域，也不传播连接或事务；输出失败不触发业务重放，不承诺审计持久性。
- [ ] 中间层仅保留/补充 cause 与业务语义，不 catch-log-rethrow；最终错误只由真实处理责任方记录一次稳定 code 和安全 cause。
- [ ] 预期业务拒绝、真实恢复与无人接盘的最终错误遵循 BIZ_/DEP_/SYS_/ENV_ 分类；WARN/INFO 无堆栈，自有数据库/缓存正常调用不新增默认 INFO。
- [ ] 实际 stdout 中成功提交、回滚和重复/幂等处理的事实次数与业务结果相符；日志不泄漏凭据、payload、query/SQL/cause 中敏感内容，不改变订单/库存/授权/租户/Outbox 原语义。

## Verification

- [OrderApplicationServiceTest](../../../services/order/order-service/src/test/java/io/github/ande1922/moduvera/reference/order/application/OrderApplicationServiceTest.java)
- [InventoryReservationHandlerTest](../../../services/inventory/inventory-service/src/test/java/io/github/ande1922/moduvera/reference/inventory/application/InventoryReservationHandlerTest.java)
- Application 单测只证明责任编排；提交、外层回滚和 commit 失败必须用生产 TransactionBoundary/持久 Adapter 与真实数据库。结合 01/02 实际 stdout/Span，在事实成立与事务结束之后断言记录次数。
- 先运行受影响用例与真实事务测试，再 `./mvnw -pl services/order/order-service,services/inventory/inventory-service -am verify`。此票使用现有事务/发布接缝，不依赖 10 的新增 Trace 存储；最终拼接在 17。

## Exclusions

不新增业务流程、状态机、审计产品或通用 after-commit 事件平台；不接管 HTTP/MQ 边界票的 canonical/最终处理实现，不无差别给所有方法加日志。

## Shared delivery constraints

- 字段、级别、安全和责任以 [日志活规范](../../../docs/logging/LOGGING.md)、[Java 绑定](../../../docs/logging/bindings/JAVA-SPRING-BOOT.md) 与 [正式 Spec](../spec.md) 为准；相应 HTTP/异步/MQ 场景细则按绑定适用条件阅读，不复活源快照的接入前 Trace 方案。
- 遵守 [交付标准](../../../docs/agents/delivery-standards.md) 和 [ADR 0034](../../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md)。窄验证通过后执行一次 Clean Code，再重跑受影响检查；不因本票自动启用 TDD 或正式 Review/Gate。
- 身份只来自可信上下文；不通过 Trace/MDC 传播权限、连接或事务。真实基础设施语义用生产 Adapter 与真实数据库/Kafka；mock 只证明格式/编排。适用异步负例按 [消息验证工作流](../../../docs/agents/message-contract-verification.md) 使用发送前 ProgressBarrier。
- 记录本票真实实现比较点、精确命令、环境、结果和证据路径；此文档中的验证项均为待执行义务，不是已有通过结果。票的实现授权不包含提交、worktree、集成、部署或 tracker 完成更新。

## Comments

- 2026-09-06：维护者确认 17 票粒度、依赖关系与落票。当前仅创建实施票；未开始实现、运行功能验证或取得交付通过证据。

- 2026-09-06：前置 02 已经独立双轴评审并快进集成，直接前置均已交付，本票解除阻塞；不在第二批 02/14 的实施范围内，未认领或开始。
