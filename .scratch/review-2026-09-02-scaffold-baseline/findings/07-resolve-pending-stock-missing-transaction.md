Type: finding
Status: rejected
Severity: major
Area: services
Claim: resolvePendingStock 没有事务边界。
Evidence: ReliableMessageConsumerFactory 用 TransactionBoundary 构造 InboxTemplate；ReliableInboundEndpoint 通过 InboxTemplate 执行业务 handler，InventoryResultMessageHandler 再调用 resolvePendingStock。
Verification: 沿 ReliableMessageConsumerFactory.forConsumer → ReliableInboundEndpoint.handle → InboxTemplate.handle → InventoryResultMessageHandler.handle → OrderApplicationService.resolvePendingStock 读取调用链与事务作用域；可运行 JdbcMessagingStoreIT 中 handler 失败时 Inbox 与业务变更共同回滚的现成测试。

# resolvePendingStock 缺少事务

## Verdict

Claim 为假。ReliableMessageConsumerFactory 把 TransactionBoundary 传入 InboxTemplate；InboxTemplate.inTransaction 在同一事务中完成 Inbox tryStart、InventoryResultMessageHandler 和 resolvePendingStock。resolvePendingStock 与 create 的显式 inTransaction 写法不同，反映的是 finding 06 的事务所有权未文档化，而不是这里所称的事务缺失。

维护者进一步指出 Factory、Endpoint 与实际事务执行者之间的职责需要明确。该问题不改变本 Claim 的反证，另行记录为 finding 19，并与 finding 06 进入同一个事务所有权 topic。
