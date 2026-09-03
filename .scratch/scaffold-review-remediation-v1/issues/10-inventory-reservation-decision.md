Type: issue
Status: ready-for-agent
Blocked by: None

# 10 — 提取 Inventory Reservation Decision

## Outcome

把 Inventory 对整个预占命令的 all-or-nothing、商品缺失、库存不足和 Reserved/Rejected 选择建模为无需数据库即可验证的纯 Domain 决策，同时保留持久化层现有并发与幂等职责。

## Acceptance Criteria

- [ ] 纯 Domain 决策以完整请求行和所需库存状态为输入，覆盖全部可用、任一商品缺失、任一商品不足、重复或边界数量等已定义组合，并只产生整体 Reserved 或整体 Rejected。
- [ ] Application Service 在现有事务边界内协调状态读取、Domain 决策、持久化应用和集成结果转换；Domain 不依赖 Spring、MyBatis、数据库锁或消息基础设施。
- [ ] Persistence Adapter 继续拥有稳定加锁、版本条件更新、command 幂等、结果唯一性和并发冲突处理，不把基础设施机制泄漏进 Domain。
- [ ] 现有异步命令、Reserved/Rejected 消息身份与 payload、Outbox/Inbox 原子性和一次性结果发布语义保持不变。
- [ ] 纯 Domain 单元测试、真实 PostgreSQL/MySQL Adapter 测试和 Application/App 集成测试分别证明业务决策、并发幂等及消息结果。

## Verification

- 运行 Inventory Domain 的决策矩阵单元测试。
- 运行真实 PostgreSQL/MySQL 的锁、条件更新、幂等和并发测试。
- 运行 Inventory Application/App 的消息契约与一次性发布测试。

## Out of Scope

- 改变消息契约、Outbox/Inbox 责任边界、事务原子性或引入新的同步 Service API。
