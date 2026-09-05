Type: issue
Status: claimed
Blocked by: 02

# tenant-only 消息边界兼容

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US02、US14、US20、US21、US22
Test seams: T07、T08、T09

## Outcome

新范围模型下，既有消息仍为 tenant-only wire；入站按每条可信消息重建 Tenant 与消费方 Actor，Platform/缺失不能使发布或消费退化为无租户执行。

## Base and scope

基于 02；通过现有消息契约、持久化和可靠入站/出站适配器做必要兼容及实证，不依赖 HTTP 或执行器装饰，不修改 provider 业务模型或 Job/Lock 产品。

## Acceptance criteria

- [ ] 既有 envelope 的 tenantid 继续必填且合法，kind/type/source/destination 及 provider-owned 常量不改变；不新增平台消息、权限字段或更强信任协议。
- [ ] 从当前执行上下文构建租户业务消息的入口要求合法 Tenant；Platform/缺失在该入口拒绝，不形成空租户消息或 Outbox 记录，相关业务写入按既有事务契约无副作用。
- [ ] 已合法构建/持久化的 tenant-only envelope 保持既有发布、恢复和投递契约；不给 Relay/Transport 额外增加原请求 Holder 前置，原请求结束或后台工作线程 Holder 为空不阻止合法消息投递。
- [ ] 入站保留契约字段与可信生产者边界验证，每条消息重建 Tenant、Initiator、Correlation 及消费方本地 Actor/权限；不信任 payload/wire 权限或工作线程偶然身份。
- [ ] 合法消息、消息契约/可信声明中的身份或租户缺失/冲突、重试/重复投递及错误退出后，上下文建立/恢复正确；工作线程旧身份与本条消息不同不是拒绝理由，不把长期监听器注册时的快照固定到全部事件。
- [ ] 通过生产数据库/消息 Adapter 与真实 PostgreSQL/MySQL/Kafka 等适用接缝证明 Inbox/Outbox 原子性、租户隔离和 tenant-only 兼容；模型或序列化单测不替代基础设施证据。
- [ ] 异步拒绝后的无业务副作用断言带消费完成屏障；测试若消息从未被消费必须不能“已通过”，不以发送前就成立的计数充当证据。
- [ ] 复用现有共享契约 TCK 和消费者，保留异步-only 不新增同步 API、协议转换仅在语义有差异时引入模型的约束。
- [ ] 必要兼容、tenant-only 接入说明、运行证据与架构/消费者回归随票交付；源码已兼容的部分记录验证，不为产生代码 diff 强行重构。

## Verification

- 遵循 [message-contract verification](../../../docs/agents/message-contract-verification.md)：框架验证通用 envelope/上下文，provider 复用 InboundMessageContractTck，实际异步副作用断言使用进度屏障。
- 复用消息 Starter 的真实 JDBC store、Outbox/Inbox、Kafka 和既有消费者 IT；断言 Handler 内上下文与退出后线程状态，以及合法/拒绝两侧持久化结果。另证明没有原请求 Holder 的后台 Relay 仍能投递已合法持久化的 tenant-only 消息。
- 首先运行受影响框架单测，再运行真实数据库/Kafka 及适用 App 消息 Scenario，记录精确命令、环境和结果；11 仅做组合回归，不代替本票基础设施义务。

## Exclusions

不改变 wire tenant 语义、引入 wire 权限/签名、建平台消息或新消费授权协议，不扩大 broker/ORM 矩阵，不顺带实施相邻可靠入站 API 或事务所有权 topic。

## Comments

- 2026-09-05：等待 02；本票负责 US20 的消息部分，Job/Lock 由 02 验证。
