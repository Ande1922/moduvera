Type: finding
Status: confirmed
Severity: major
Area: services
Claim: Inventory 的“库存预占决策”由 MybatisInventoryStore 实现；数据库并发与 commandId 幂等机制归属正确，但 Reserved/Rejected 的业务判断缺少独立、可单元验证的 Domain 表达。
Evidence: inventory-service 的 Domain 只有 InventoryStore 与不含业务行为的 ReservationDecision；MybatisInventoryStore 在数据库锁内同时判断缺失/不足商品、选择 Reserved 或 Rejected 并构造集成结果。SQL Mapper 另外正确承担行锁、版本条件更新和结果唯一插入。
Verification: 读取 InventoryStore、ReservationDecision、InventoryApplicationService、MybatisInventoryStore 与 InventoryMapper，区分 all-or-nothing/不足判断等业务决策和加锁、条件更新、唯一约束、commandId 幂等等持久化机制。
Planned: .scratch/scaffold-review-remediation-v1/spec.md
Fixed: 0109e0df3747b6ae5ed463198178563618670894

# Inventory 规则集中在持久化 Store

## Verdict

修正后的 Claim 确认成立。维护者接受在下一版本把 all-or-nothing、缺失/不足库存和 Reserved/Rejected 选择建模为纯 Domain 决策，由 Application Service 转换为集成结果；Persistence Adapter 继续拥有稳定加锁、条件扣减、版本检查、结果唯一约束、commandId 幂等及并发冲突处理。

该修复不得改变现有异步消息契约、Outbox/Inbox 责任边界或数据库事务原子性。测试应分别由 Domain 单元测试证明业务决策、真实 PostgreSQL/MySQL Adapter 测试证明并发与幂等、Application 测试证明新结果只发布一次。
