Status: resolved
Labels: resolved
Blocked by: None

# 15 — 加固 Durable Publication 的低延迟、多实例与生命周期可靠性

## Problem Statement

当前消息基础设施已经具备本地事务内持久化 Outbox、批量 claim、同步等待 Kafka ACK、失败重试、lease 过期恢复、token 匹配更新、Inbox 去重和消费侧 DLQ 等基本能力，能够提供从业务事务提交到 Broker 接受消息的 at-least-once 发布语义。但是，当前 Relay 主要依赖固定间隔调度；单次调度只处理一个批次；多实例顺序范围、claim 生命周期、进程停机、GC 停顿、过期 claim 接管和滚动发布的行为还没有形成完整且可测试的契约。

这些缺口不会改变 Outbox 的责任边界，却会在正常运行中引入额外投递延迟，在突发积压时降低恢复速度，并在多实例、长耗时发送或进程切换时扩大重复投递和暂时停顿的窗口。现有 claim 查询还只按原始 `partitionKey` 判断前序消息，无法准确表达已经确认的 `destination + partitionKey` 顺序域。与此同时，必要索引、已发布记录清理、终态记录人工重投和有界可观察性不能被推迟到出现数据库瓶颈之后才设计。

本规格需要把已经讨论确认的机制收敛成一个可实现的 P0：数据库仍是唯一事实来源；本地 after-commit 信号只负责低延迟唤醒；单实例内只有一个连续拉取 Worker；实例之间只通过数据库原子 claim、批次 token 和有期限 lease 协调；所有发送以 Broker ACK 为完成边界；过期接管可能产生重复但不得造成消息永久丢失。该机制只承诺 at-least-once，不承诺 exactly-once、严格无重复 Broker 日志或消费后的业务收敛。

## Solution

把 Durable Publication 明确拆成三个协同但边界清晰的部分：业务事务只持久化发布意图并在提交后发出不含消息数据的本地唤醒信号；每个进程只有一个互斥 Relay Worker，在唤醒或兜底轮询后连续 claim 并处理多个有界批次；多个进程通过数据库时间、原子 claim、批次级 claim token、过期 lease 和 token-fenced 状态更新安全分工。

每次数据库 `claim(limit)` 构成一个批次，同一批次使用一个新的 claim token，但每条状态更新仍以 `messageId + claimToken` 作为 fencing 条件。一个批次只由一个实例顺序处理，不拆分转交。查询只允许每个 `destination + partitionKey` 的最早可发布记录进入 claim，因此不同 key 可以并行，同 key 的后继记录必须等待前驱得到 Broker ACK 并标记 `PUBLISHED`，或前驱进入明确的 `TERMINAL` 状态。任何健康实例都可以认领任何符合条件的记录，不建立稳定实例所有权、Key Lane、Leader Election 或 Redis 分布式锁。

lease 由数据库时钟颁发。P0 不在每条发送前刷新数据库 lease，而是在一次批量 claim 后建立保守的本地单调时间 deadline，并为 Kafka send 配置严格有界的 ACK timeout。每条消息开始发送前只检查本地 deadline；deadline 已到则不再开始余下发送，由 lease 到期后的其他实例接管。已经进入 Kafka send 的调用仍可能跨过 lease 到期点并与接管实例形成重复投递，旧 token 随后的状态更新必须失败；该窗口由 at-least-once 和 Inbox/业务幂等吸收，而不是以 N 级数据库 heartbeat 消除。

Relay 在本地采用 `WAITING`、`RUNNING`、`STOPPING` 生命周期。after-commit 信号只在等待时触发运行；运行期间的信号不得创建第二个任务，只能通过原子“可能仍有工作”状态避免 `RUNNING -> WAITING` 边界丢失唤醒。一次运行持续拉取，直到查不到合格记录、收到停止请求或达到配置的运行预算。周期轮询始终保留，用于恢复漏信号、进程重启和其他实例遗留的工作。

同时补齐信封契约、版本规则、必要索引、数据生命周期、终态人工重投、指标和真实并发/故障验证。单独的性能基线工作在本 issue 完成后执行，并只在证据显示数据库成为瓶颈时启用更激进的优化方向。

## User Stories

1. As a 业务开发者, I want 业务状态与 Outbox 发布意图在同一个本地事务中提交, so that 事务成功后消息不会因进程故障永久丢失。
2. As a 业务开发者, I want 事务回滚时对应 Outbox 记录也回滚, so that 未发生的业务事实不会被发布。
3. As a 平台使用者, I want Durable Publication 的保证明确结束于 Broker ACK, so that 消费、补偿、冲正和最终业务状态不会被误认为 Outbox 的职责。
4. As a 平台使用者, I want 系统只承诺 at-least-once, so that 发送成功但状态标记失败的歧义窗口可以用相同 Message ID 重投并由消费端幂等处理。
5. As a 业务开发者, I want 消息 Topic/Destination 默认不包含版本, so that路由名称不会成为第二套版本系统。
6. As a 业务开发者, I want Message Type 包含破坏性变更的主版本（例如 `.v1`）, so that 兼容字段扩展保留原类型，破坏性变更可以 consumer-first 演进为新类型。
7. As a 消费者, I want 在入口验证必填信封字段以及预期的 kind、type、source 和 destination, so that 错路由或错误契约在执行业务前被拒绝。
8. As a 安全维护者, I want 从线上的消息信封与持久化模型删除 `actorPermissions`, so that 发送方声明不能被误用为消费方授权凭证。
9. As a 安全维护者, I want source 校验只被定义为可信内网 Kafka 中的契约校验, so that 当前实现不会虚假宣称消息签名或密码学来源认证。
10. As a 消费方开发者, I want 消息入口建立本服务需要的可信执行上下文, so that 权限判断不依赖发送者携带的权限集合。
11. As a 请求处理线程, I want 在包含 Outbox 写入的事务真正提交后发出本地唤醒信号, so that 正常消息无需等待完整轮询周期。
12. As a Relay, I want 唤醒信号不携带消息 payload 或记录副本, so that 数据库始终是唯一发布事实来源。
13. As a Relay, I want 周期轮询始终作为兜底, so that 漏信号、重启或其他节点遗留任务仍能恢复。
14. As a 单个应用实例, I want 任意时刻最多只有一个 Relay Worker 运行, so that after-commit 和定时调度不会在本机形成重叠 claim。
15. As a 运行中的 Worker, I want 新唤醒不创建第二次执行, so that 本机互斥不依赖脆弱的调度时序。
16. As a Worker, I want `RUNNING -> WAITING` 转换与“可能仍有工作”状态原子协调, so that 最后一次空查询附近提交的新记录不会等待到非必要的下一个轮询周期。
17. As a Worker, I want 一次触发后连续 claim 多个批次, so that 当前积压可以被连续拉取并清空，而不是每批固定等待一个调度周期。
18. As a Worker, I want 连续运行在无可用记录、停止请求或运行预算达到时退出, so that单实例不会无限占用执行资源。
19. As a 平台配置者, I want “批次”被严格定义为一次原子 `claim(limit)` 返回的记录集合, so that batch size、lease 和发送预算具有可推导含义。
20. As a 平台配置者, I want 一次运行可以处理多个批次但一次只有一个活动批次, so that吞吐提升不引入本机并发发送语义。
21. As a 多实例部署维护者, I want 每个实例拥有运行期唯一身份但消息不永久归属某个实例, so that Pod 重启、扩缩容和滚动发布无需迁移所有权。
22. As a 多实例部署维护者, I want 所有实例通过数据库原子 claim 和 `FOR UPDATE SKIP LOCKED` 分工, so that 不需要 Leader Election、Redis 锁或进程外单点协调器。
23. As a 多实例部署维护者, I want 每次批量 claim 生成一个新的批次级 claim token, so that 一个 token 可 fence 整批所有权而无需为每条消息生成 token。
24. As a 多实例部署维护者, I want 发布成功、失败和终态更新都匹配 `messageId + claimToken`, so that 旧实例恢复后不能覆盖新实例已经接管的记录。
25. As a 多实例部署维护者, I want 一个批次只由认领它的实例处理且不进行部分转交, so that 批次内发送和状态更新不会被多个实例交错执行。
26. As a 多实例部署维护者, I want lease 使用数据库时钟创建, so that 应用节点时钟漂移不会改变 claim 所有权期限。
27. As a 性能维护者, I want P0 一次数据库批量 claim 后仅使用本地单调时间判断是否还能开始发送, so that 不为批次中的每条消息增加一次 lease heartbeat 数据库调用。
28. As a 性能维护者, I want Kafka send 具有严格有界的 Broker ACK timeout, so that 一个挂起调用不会无限跨过 lease 和停机期限。
29. As a Relay, I want 在每条发送开始前检查保守的本地 deadline, so that GC 停顿或异常耗时恢复后不会继续发送已经明显失去 claim 的剩余记录。
30. As a Relay, I want 已过本地 deadline 的未发送记录留待 lease 到期接管, so that 旧实例不会主动清除或继续使用失效 claim。
31. As a 平台使用者, I want 接受已经进入 Kafka send 的调用在 lease 过期后可能重复, so that 系统不会以昂贵的逐消息数据库 fencing 虚假承诺 exactly-once。
32. As a 消费者, I want 重复投递保留相同 Message ID 并由 Inbox 与业务幂等吸收, so that 生产端的歧义窗口不会重复产生业务副作用。
33. As a 业务聚合维护者, I want 顺序域定义为 `destination + partitionKey`, so that 同一个业务 key 在不同目标上的消息不会互相阻塞。
34. As a 业务聚合维护者, I want 同一顺序域一次只允许最早的可发布记录被 claim, so that 后继消息不会因 `SKIP LOCKED` 绕过仍在处理或退避的前驱。
35. As a 业务聚合维护者, I want 前驱只有在 `PUBLISHED` 或明确 `TERMINAL` 后才释放后继, so that 可重试失败保持 key 内顺序，而终态毒消息不会永久冻结后续业务。
36. As a 业务聚合维护者, I want 不同顺序域可由同一批次或不同实例并行处理, so that 单点业务故障不会阻塞全局。
37. As a 运维人员, I want Relay 停机时先进入 `STOPPING` 并停止新 claim, so that 滚动发布不会不断扩大旧实例的未完成工作。
38. As a 运维人员, I want 正常停机在配置的 grace 内完成当前已开始发送和有界批次, so that 大多数滚动发布不产生可见中断。
39. As a 运维人员, I want 强制停机时依赖 lease 自然到期接管而不是无条件清除 claim, so that 尚不确定的 in-flight send 不会被错误判定为未发送。
40. As a 运维人员, I want claim 数量、lease、send timeout、运行预算和 shutdown grace 具有可校验的不变量, so that 单个批次的最坏执行时间不会在正常配置下系统性越过 lease。
41. As a 运维人员, I want 查询 `TERMINAL` 记录并以原 Message ID 人工重投, so that 人工修复后的消息可以重新进入正常发布链路。
42. As a 运维人员, I want `PUBLISHED` 记录在可配置保留期后以小批次清理, so that Outbox 表不会无限增长或被长清理事务阻塞。
43. As a 运维人员, I want `TERMINAL` 在显式处置前不自动删除且 `PENDING`/claimed 不参与普通清理, so that 未完成和待人工处理的发布意图不会被生命周期任务误删。
44. As a 运维人员, I want Inbox 保留时间不短于 Broker 保留期加允许重放窗口, so that 合法重放不会因去重记录过早删除而重复执行业务。
45. As a 数据库维护者, I want claim、同 key 前驱判断、已发布清理和终态重投从首版就有与方言匹配的索引, so that 正确性机制不会以全表扫描上线。
46. As a 运维人员, I want 观察 pending 数量与最老年龄、terminal 数量、发布成功失败、ACK 延迟、重试、claim 冲突和 stale-token 更新, so that 积压、Broker 故障和接管竞争可以被及时发现。
47. As a 安全维护者, I want 指标只使用 service、destination、type、result 等有界标签, so that tenant、user 和 Message ID 不会造成高基数或敏感信息泄漏。
48. As a 日志使用者, I want Message ID 等实例级标识只进入安全日志或 trace 且不记录 payload、token 或权限, so that 单条消息可排障而不暴露敏感数据。
49. As a 发布维护者, I want 新旧版本实例在滚动期间都能读取既有 Outbox 记录并安全竞争 claim, so that mapper 或信封变化不会让升级前持久化的记录无法发布。
50. As a 发布维护者, I want 旧记录的最终 wire contract 在升级期间保持向前兼容, so that发送时重新编码不会静默改变尚未发布记录的业务含义。
51. As a QA 工程师, I want 通过真实 PostgreSQL 和 Kafka 验证多实例、停机、GC 停顿和 ACK 歧义窗口, so that 可靠性结论不只来自内存 Store 或单线程单元测试。
52. As a 平台维护者, I want 在正确性加固完成后建立独立性能基线, so that 后续数据库优化由 p95/p99 延迟、吞吐和数据库证据驱动。

## Implementation Decisions

- Durable Publication 的强保证只覆盖“业务状态与发布意图同事务持久化”到“Broker 确认接受”。Outbox 不跟踪是否被消费、消费是否成功或业务是否最终收敛；消费侧 retry/DLQ 属于 Consumer Adapter/Binder，Inbox 只负责事务性去重标记。
- 发布语义固定为 at-least-once。发送已成功但发布状态未能落库时允许以相同 Message ID 重复发送。首版不引入 Kafka transaction、XA、跨 Broker/数据库 exactly-once 或严格无重复日志承诺。
- Message Type 是破坏性消息契约版本的唯一规范位置，例如 `.v1`。兼容字段增加保持原 Message Type；破坏性变更引入新类型并采用 consumer-first 滚动。Destination 和 Topic 默认稳定且不带版本；不得再让独立 `schemaVersion` 承担与 Message Type 重复的路由含义。
- 线上的标准信封和 Outbox 持久化模型删除 `actorPermissions`。消费入口验证 kind、type、source、destination 和必填字段。当前边界限定为可信内网 Kafka；source 是契约字段，不是签名身份。消息签名、外网 Kafka 和强生产者认证另行设计。
- 业务事务提交后发布一个进程内 wake signal。signal 不携带 payload、Outbox ID 列表或可独立投递的副本。事务回滚不触发可见工作；即使 signal 丢失，轮询仍必须发现已提交记录。
- 每个应用进程只有一个 Relay Worker。生命周期至少区分 `WAITING`、`RUNNING`、`STOPPING`。wake 与 timer 使用同一互斥入口；运行中 wake 不创建第二个执行，只维护避免结束边界丢信号所需的原子状态。
- Worker 一次运行连续执行 `claim(limit) -> 顺序发送本批次 -> 再次 claim`。结束条件是无合格记录、停止请求或运行预算达到。批次仅指一次原子 `claim(limit)` 的结果，不等同于一次 wake 或一次完整运行。
- 多实例不设置稳定消息所有者。任何属于同一逻辑服务、拥有相同数据库和路由配置的实例都可认领合格记录。实例身份只用于运行期日志/诊断，不新增 `claim_owner` 持久化字段。
- PostgreSQL 使用单条原子 claim 与 `FOR UPDATE SKIP LOCKED`。MySQL 保留方言专属、同等语义的 claim 实现并通过兼容 TCK；在并发证据完成前不得仅凭单实例测试宣称生产等价。
- 每次 `claim(limit)` 生成一个批次级随机 token，并把同一 token 写入该批次各行。发布、失败、终态以及必要的释放/修复操作均以 `messageId + claimToken` 作为 fencing 条件。token 不证明实例身份，只证明当前一轮 claim。
- 一个批次不跨实例拆分。进程退出后未完成行保持已 claim 状态，直到当前实例在有效 token 下完成，或 lease 到期后被其他实例重新 claim。首版优先使用可推导的小批次和严格 timeout，不增加 lease heartbeat。
- lease 起点与到期时间由数据库时钟确定。应用在 claim 调用前记录本地 monotonic 起点，并根据配置 lease、调用耗时和安全裕度建立不晚于数据库 lease 的保守 deadline；不得用应用 wall clock 与数据库时间直接比较。
- 每条发送开始前只检查本地 monotonic deadline。超时后不开始剩余 send，也不再使用旧 token主动改写状态。Kafka send 必须同步等待 Broker ACK 并具有严格 timeout。已经进入 send 后跨 lease 到期造成的重复属于允许的 at-least-once 窗口。
- 配置校验必须保证 `batchSize × 单条最坏 send timeout + 数据库状态更新时间 + safety margin` 能被 lease 与 shutdown grace 覆盖，或通过更保守的批次/预算约束达到等效结果。默认值应由真实集成和性能测试校准，不在本规格写死。
- `attemptCount` 表达实际发生并完成判定的发送失败，而不是单纯取得 claim。正常滚动、未开始发送或仅等待 lease 接管不得在没有发送结果时耗尽重试上限。
- 顺序作用域为 `destination + partitionKey`；若租户需要隔离顺序，tenant 必须稳定编码进 partitionKey，或由路由契约明确纳入等价键。不同 scope 可并行，同一 scope 只允许最早的非终结发布意图进入活动状态。
- 前驱为 retryable failure/backoff 或有效 claim 时，后继仍不可 claim。前驱标记 `PUBLISHED` 后后继可由任一实例认领；前驱进入显式 `TERMINAL` 后也释放后继，避免平台为业务强加永久 key freeze。极端 in-flight 歧义可能导致较早消息的重复副本晚于后继到达，消费端仍以 Message ID 幂等。
- 正常停机先拒绝新的 claim，再在 shutdown grace 内完成已开始的 send 和当前有界批次。不得在 in-flight send 结果未知时清除 claim。超出 grace 时允许退出并等待 lease 接管；这可能带来一个 lease 级别的暂时延迟和重复，但不得丢失消息。
- 提供查询终态记录和显式 redrive 的平台能力。redrive 保留 Message ID 和原业务事实，把记录安全恢复为可认领状态；最终是否能被消费以及消费后的业务处理不属于 Outbox 定义。
- 首版必须设计四类数据库访问路径：可认领 pending 扫描、同顺序域前驱判断、按保留期批量清理 published、查询/重投 terminal。PostgreSQL 优先使用与状态相符的 partial index；MySQL 使用可利用的 composite index。索引列序、条件和 migration 按实际方言分别验证，不追求逐字 SQL 统一。
- `PUBLISHED` 在可配置保留期后小批量删除；`TERMINAL` 在显式处置前永久保留；`PENDING`、backoff 和有效/过期 claim 不参加普通清理。清理使用有界批次与短事务，不能长期阻塞 claim。归档只在部署合规需要时增加，不默认建设归档系统。
- Inbox 保留期至少覆盖 Broker retention 与允许人工/灾备重放窗口之和。若无法证明该不变量，默认不自动清理 Inbox。
- 指标覆盖 pending count/oldest age、terminal count、claim 数量/冲突、stale-token 更新、publish result/latency、retry 和 cleanup。标签限定为 service、destination、message type、result 等有限集合；tenant、actor、message ID、claim token 只能进入安全日志或 trace。
- 新 Relay 必须兼容升级前已经持久化但尚未发送的 Outbox 记录。当前“保存描述字段与业务 payload、发送时编码”的模型不得因新 mapper 替换而改变旧记录语义；实现可以选择版本化兼容 mapper或持久化不可变 canonical envelope，但必须由滚动测试证明，不能仅靠文档约定。
- Key Lane 所有权、同 key 一次 claim 多条、Leader Election、Redis 分布式锁和逐消息 lease heartbeat 均不在 P0。只有性能基线证明当前数据库协调成为主要瓶颈后才重新评估。

## Testing Decisions

- 最高测试 seam 是 Reliable Publication 模块的公开写入、claim、Relay 生命周期、redrive 与指标接口，配合真实 PostgreSQL、真实 Kafka 和黑盒参考产品。单元测试可验证状态机，但不得把私有 Worker 方法或 SQL 逐字文本当作主要完成证据。
- 保留现有业务状态 + Outbox 同提交同回滚测试，并增加事务提交前/回滚后不产生有效 wake、提交后 wake 显著低于轮询周期触发发布、丢失 wake 后轮询仍恢复的验证。
- 本地 Relay 生命周期测试覆盖：多个并发 wake/timer 只运行一个 Worker；运行期 wake 不产生重叠任务；`RUNNING -> WAITING` 竞争不丢工作；一次运行连续处理多个批次；无工作、预算耗尽和 stop 均能有界退出。
- PostgreSQL 集成测试必须启动两个独立 Relay/Store 实例同时 claim，证明一条记录只被一个有效 token 更新、同一 `destination + partitionKey` 只有最早记录可认领、不同 key 可以并行、外层 `SKIP LOCKED` 不会让后继绕过仍可见的前驱。
- lease 测试覆盖数据库时间创建 lease、过期后其他实例接管、旧 token 的 published/failed/terminal 更新被拒绝、批次 token 在批次内共享但不同批次不同、同一批次不被拆分处理。
- GC/长停顿测试使用可控 clock 或执行栅栏证明：停顿在 send 前且本地 deadline 已到时不再发送余下记录；停顿发生于已进入 send 后允许出现重复，但至少一个实例最终把记录标记为 `PUBLISHED`，且没有永久丢失。
- Kafka 故障测试覆盖 Broker 正常、缓慢、不可用和 ACK timeout。测试发送成功/状态落库前崩溃与 timeout 后 Broker 实际接受两类歧义窗口，断言重复沿用原 Message ID，并由 Inbox/业务幂等只产生一次副作用。
- 顺序测试覆盖同 scope 的 A1/A2/A3、不同 scope 的并行消息、A1 retryable backoff 阻塞 A2、A1 `PUBLISHED` 后 A2 可由另一个实例认领、A1 `TERMINAL` 后 A2 释放，以及 destination 不同但 partitionKey 相同不互相阻塞。
- graceful shutdown 测试覆盖停止后不再 claim、当前有界批次在 grace 内完成、未知 in-flight send 不清 claim、强制退出后 lease 到期由新实例接管。滚动测试至少同时运行旧版本与新版本，证明持续写入和发布无永久中断。
- 兼容性测试预置升级前格式的 Outbox 行，再由新版本 Relay 发布并由旧/新消费者验证 wire contract。Message Type `.v1` 的兼容字段增加继续可读，破坏性 `.v2` 使用 consumer-first 路径；Topic/Destination 不因版本改变。
- 信封测试覆盖删除 `actorPermissions` 后的序列化和数据库 migration、必填 kind/type/source/destination 校验、未知类型/来源拒绝以及消息入口执行上下文清理。不得测试或宣称未实现的签名认证。
- terminal/redrive 测试覆盖可查询、显式重投、保留原 Message ID、重新进入 claim、stale token 无法 redrive，以及后继在前驱 terminal 后可继续。消费结果不作为 Outbox redrive 成功的定义。
- lifecycle 测试使用真实 migration 和足够数据验证必要索引可被关键查询使用、published 只在 retention 后按小批次删除、terminal/pending/claimed 不误删、cleanup 与 claim 可并发完成。PostgreSQL 与 MySQL 分别验证自己的索引和 SQL。
- 指标测试断言关键 counter/gauge/timer 的含义与结果，且 label 集合中不存在 tenant、actor、message ID、claim token 或异常文本。日志测试至少证明 payload、token 和权限不被默认输出。
- MySQL compatibility TCK 必须覆盖 claim 原子性、同 key 前驱、lease 接管、token fencing、cleanup 和 redrive。若多 Worker 证据未完成，MySQL 能力状态必须保持 incubating 或明确受限。
- 完成门槛包含模块级测试、真实 PostgreSQL/Kafka 集成、独立 Starter consumer smoke、黑盒参考产品故障恢复和滚动场景。只通过内存 Store、单 Worker 或 happy-path Kafka 发送不能关闭本 issue。
- 本 issue 完成后执行 16 号性能基线 issue。性能测试关注 commit-to-Broker-ACK p50/p95/p99、吞吐、数据库 statements/message、CPU/I/O/lock waits、oldest pending、claim conflict 与重复率；它不替代这里的正确性验证。

## Out of Scope

- 消费后的业务成功、跨事务回滚、反核销、冲正、补偿、Saga、流程编排或对业务 handler 的通用暂停策略。
- Outbox 追踪 consumer 是否成功、由 producer 决定毒消息后继是否继续、或把 Inbox 扩展为 retry/DLQ 调度器。
- exactly-once、分布式事务、Kafka transaction 与数据库原子提交、严格无重复 Broker 日志或在所有故障下维持严格事件到达顺序。
- Key Lane 长期所有权、每个 partitionKey 绑定固定实例、同 key 单次 claim 多条、全局 Leader Election、Redis/etcd/ZooKeeper 锁。
- 每条消息发送前刷新 lease、常驻 lease heartbeat、为每条消息生成独立 token，或新增仅用于诊断的 `claim_owner` 数据库字段。
- 外网 Kafka、消息签名、强制 mTLS、通用生产者身份认证协议或基于信封 `source` 的密码学授权。
- Topic 名称版本化、为兼容字段增加新 Topic，或同时维护 Message Type、Topic 和独立 schemaVersion 三套版本选择。
- 默认归档系统、无限期保存全部 published 记录、自动删除未处置 terminal，或未经保留期不变量验证的 Inbox 清理。
- 在没有性能证据前引入 CDC/Debezium、Outbox 表分区、批量状态更新、多线程 Relay 或新的分布式协调组件。

## Further Notes

- 2026-08-31 实施时确认项目仍处于早期开发阶段，数据库表结构将在定稿后重新整理基线。因此本次按明确决策直接从 wire、领域模型和 PostgreSQL/MySQL V1 基线删除独立 `schemaVersion` 与 `actorPermissions`，不保留 expand/contract 兼容列，也不要求旧二进制与新表结构并行运行。新 mapper 对旧 wire 中的多余字段保持忽略，但数据库滚动兼容门槛由该决策取代。
- Kafka 物理 Topic 统一为小写横杠分段且不带版本号，DLQ 使用 `-dlq` 后缀。Starter 在生产、消费和 DLQ binding 装配时 fail-fast；reactor 架构测试扫描全部应用配置默认值，避免集成测试覆盖配置后让违规 Topic 漏过提交门禁。
- 完成证据包括：Relay/Worker 确定性单元测试；PostgreSQL 双 Store 并发、lease、fencing、顺序、redrive、cleanup 与 planner 验证；MySQL 对等 TCK；真实 Kafka 的 Notes 重复投递/Inbox 去重；Order 与 Inventory 应用级 PostgreSQL+Kafka 集成；全 reactor 非容器 `verify`；以及参考产品 Kafka 中断、业务与 Outbox 提交、恢复重启接管的黑盒验证。
- 本规格补充并深化已经完成的 03 号 Outbox-to-Kafka 和 11 号故障恢复 issue，不把它们改回未完成。完成状态必须以本 issue 新增的多实例、生命周期、滚动兼容、索引清理和观测证据为准。
- 已接受的架构边界记录在 Durable Publication、Message Type 版本和无 wire permissions 三份 ADR 中。实现如果需要改变这些边界，应先新增或修订 ADR，而不是在代码中隐式偏离。
- 当前实现可作为 prior art：定时单线程 Relay、批量 claim、批次 token、lease 过期、stale-token 拒绝和同步 `acks=all` 均应尽量演进而非平行重写。主要差异是 after-commit wake、连续 drain、完整状态机、数据库时间 lease、保守本地 deadline、目标顺序域、生命周期操作和多实例证据。
- 首次实现应偏向小批次、短且严格的 send timeout、可推导的 lease 与 shutdown grace。默认参数必须通过真实 Kafka/数据库测试校准，并允许部署按环境调整。
- 16 号 issue 是明确的后续性能待办。必要索引、短事务清理和查询路径属于本 P0，不得以“等性能测试后再做”为由推迟；自适应批次、批量状态更新、表分区、更高 Relay 并发和 CDC 才属于有证据后的优化方向。

## Answer

逐项证据复核确认该 P0 由 `71d3f37` 实现，后续 JDBC runtime-adapter
证据由 `2409dc2`、`4dab6fa` 等提交继续验证。当前实现包含 after-commit
wake 与连续单 Worker 状态机、数据库时间 lease、批次 token fencing、
`destination + partitionKey` 顺序域、ACK timeout、terminal redrive、
published cleanup、低基数指标以及 PostgreSQL/MySQL 专属索引。

验证证据位于 `OutboxWorkerTest`、`OutboxRelayIT`、`JdbcMessagingStoreIT`、
`JdbcMessagingMySqlIT`、真实 Kafka App tests 和参考产品故障恢复 harness；
本次校准运行消息 Starter 七模块聚焦 `verify`，结果 PASS。官方 Normal
quality gate 在固定基线的 `ModuveraMonolithApplicationIT` V2 migration
assertion 失败；外部 Ticket 09 的 `542fdc5` 已修复该基线缺口，但不属于当前
HEAD ancestry。
