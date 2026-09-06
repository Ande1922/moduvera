Type: spec
Status: needs-triage
Fixes: review-2026-09-02-scaffold-baseline/08

# 跨执行模型的 ExecutionContext 与规范化传播

## Problem Statement

Scaffold 已有不可变 ExecutionContext、ThreadLocal Holder 和显式任务快照，但尚未形成跨同步、执行器、回调和响应式场景的一致接入契约：

- 当前模型以具体 TenantId 为必填项，不能表达合法的非租户平台入口；上下文缺失又不能被解释为平台身份或全局权限。
- 当前 Snapshot 只有严格捕获与 Runnable/Callable 包装，未区分通用执行器的提交时点、Future/SDK 回调的注册时点和原生响应式载体的读取时点。
- ThreadLocal 不会因返回 Flux/Future 就覆盖后续工作；Spring AI 工具循环和流式响应中的业务回调也不能依赖执行线程偶然携带上下文。
- 当前 Scope 允许 null、以 context 对象比较关闭次序，无法拒绝同一对象重复嵌套时的错误逆序；Callable 包装还会改变受检异常形状。这些差距需与新增能力一起修订。
- 仅验证 Tenant ID 相同不足以证明没有串请求；Actor、Initiator、Correlation、缺失状态以及恢复目标同样属于隔离契约。

目标是提供一个恢复内核和规范化的场景适配方式，而非透明接管所有线程、构建认证产品或把上下文参数铺满业务接口。当前代码与测试证据见 [Grill 草案的差距清单](interface-draft.md#9-当前代码差距与验证接缝)；这些发现不是本轮已完成修复或功能测试结果。

## Solution

以同一个不可变 ExecutionContext 表达 `Platform` 或 `Tenant(id)` 执行范围，保留 ExecutionContextHolder 作为需要执行上下文的业务接缝和基础设施的统一读取入口。使用显式、可恢复的 Scope 实现严格捕获、允许缺失捕获和可信上下文安装，再在各执行边界选择合适的传播方式。

| 边界 | 标准用法 | 绑定时点与恢复范围 |
| --- | --- | --- |
| 同步入口、可信临时切换 | 显式安装目标上下文 | 当前同步作用域，退出恢复 |
| JDK/Spring 受管执行器 | 可复用执行器装饰 | 每次直接提交捕获，实际任务执行期间恢复 |
| 请求归属的 Future/SDK 回调 | 具名快照绑定对象 | 注册回调时捕获，实际同步回调期间恢复 |
| 长期事件监听 | 从每条事件的可信入口信息重建 | 每次事件处理独立建立；不固定注册监听器时的身份 |
| Reactor | 订阅 Context 与局部同步回调模板 | 原生载体传递，每次实际回调恢复 Holder |
| Spring AI | 本次请求原生 context、ToolContext 与无状态读取适配器 | 每次实际 Tool/响应处理读取本次上下文并恢复 Holder |

这些是不同边界的接入方式，不要求在同一个边界叠加三层手工包装。不同边界可以组合，但不得丢失、重新猜测或借用另一逻辑执行的身份。

### 决策基线与规格状态

- 用户已整体确认 [接口草案](interface-draft.md) 的核心契约、公共接缝、测试接缝及首期边界；本规格收敛该决定，不重新打开已完成的 Grill。
- 本文件是该能力唯一的 tracker spec；草案保留为决策来源和示例，不作为第二份并行规格。
- `needs-triage` 表示本轮产出的正式规格仍待审阅，不否定设计基线已确认，也不意味着实现已开始。不得标记 resolved、关闭原 finding 或自动拆票。

## User Stories

1. **US01 — 明确执行范围。** 作为入口接入方，我能显式建立 Platform 或合法 Tenant(id)；两者都有完整 Actor、Initiator、Correlation。没有上下文是第三种状态，不是 Platform。
2. **US02 — 分层严格读取。** 作为需要身份的调用方，我能要求 ExecutionContext 存在；作为租户资源调用方，我还能要求具体 Tenant。缺失上下文和已有 Platform 但缺少 Tenant 均失败，不转成无过滤查询。
3. **US03 — 严格捕获。** 作为业务回调注册方，我在注册时要求并固定上下文；缺失时立即失败，而不是把问题延迟到工作线程。
4. **US04 — 通用缺失传播。** 作为执行器适配方，我可以捕获明确的缺失状态；即使工作线程或 inline 调用方已有另一个上下文，任务期间也看不到它，退出后仍正确恢复。
5. **US05 — 可信临时切换。** 作为已完成外部授权的接入方，我能安装可信目标上下文并临时执行租户工作；正常或异常退出后恢复原范围。能力本身不授予目标租户权限，不修改原始身份记录。
6. **US06 — 安全 Scope。** 作为同步代码调用方，我能嵌套安装上下文；同一对象重复安装也必须逆序关闭，错误线程或错误次序的关闭不改变状态，合法关闭后重复关闭幂等。
7. **US07 — 异常透明。** 作为任务调用方，我获得原 Runnable/Callable 的返回或异常语义；上下文包装不新增私有受检异常转运行时包装，并在真正退出时恢复线程状态。
8. **US08 — 标准执行器。** 作为 JDK/Spring 使用方，我能装饰选定执行器；平台线程池、每任务虚拟线程、inline 与 CallerRuns 使用相同捕获规则，不改变拒绝、Future、取消和关闭契约。
9. **US09 — 注册时绑定回调。** 作为 CompletionStage/SDK 使用方，我能绑定常用单参、双参及无参回调；Future 已完成而立即回调、被其他线程完成或异步调度时，业务仍读取注册方上下文。
10. **US10 — 复用边界可见。** 作为维护者，我能从类型、名称和 Javadoc 识别固定快照对象；普通函数可共享，但固定快照不得跨请求复用，即使请求属于同一租户。
11. **US11 — 生命周期独立。** 作为异步调用方，我能让已提交任务在父 Scope 退出后继续，同次执行的回调也可重试或多次调用；子任务切换不回写父任务，取消/超时通知不提前撤销仍在运行的 Scope。
12. **US12 — HTTP 入口分型。** 作为 App 接入方，我明确选择受管业务处理器和非业务排除项；受管入口按方法声明、类声明、默认 TENANT 的次序解释范围，不因漏写注解阻止应用启动。
13. **US13 — 合法非租户主体。** 作为平台入口调用方，我可以使用可信、已认证但没有 tenant 断言的 USER；同一主体不能进入要求 Tenant 的入口，也不能通过 Header 自动切换平台入口范围。
14. **US14 — 范围不授予权限。** 作为接入平台管理员或多级经销商的应用，我能共用 Platform/Tenant 模型，再由自己的权限与资源范围策略限制操作；平台范围、入口排除及上下文切换都不绕过授权。
15. **US15 — Reactor 原生传递。** 作为响应式调用方，我能显式写入可信上下文，或在调用传播模板时严格捕获 Holder，并从订阅 Context 严格读取，不依赖订阅线程上的偶然值。
16. **US16 — Reactor 业务回调。** 作为流式处理方，我能在指定的同步映射回调里使用 Holder；不同订阅、scheduler、错误、重试与取消不串上下文，缺失键不能借用工作线程身份。
17. **US17 — AI 单次请求注入。** 作为 AI 接入方，我对一次调用捕获同一个可信 ExecutionContext 并放入 Advisor request context 与 ToolContext；不污染共享 ChatClient 默认配置，也不把身份塞进模型输入。
18. **US18 — AI 工具循环。** 作为工具实现方，我通过统一 ToolCallback 装饰器读取 Holder；多轮 ToolCallingAdvisor 及中途换线程后仍使用本次 ToolContext，缺失或错误类型在委托执行前失败。
19. **US19 — AI 流式响应处理。** 作为响应消费方，我在保留 ChatClientResponse 的阶段用无状态响应适配器恢复 Holder；每次读取当前响应的上下文，处理成功或失败后恢复原线程状态。
20. **US20 — 现有消息与任务兼容。** 作为现有消费者，我继续使用 tenant-only 消息协议和消费方 Actor 策略；Job/Lock 只做必要兼容，GLOBAL 锁竞争范围不被解释为 Platform 执行权限。
21. **US21 — 依赖与调用兼容。** 作为不使用 Reactor/AI 的 Kernel 消费者，我不被迫引入这些框架；既有 Tenant 构造、严格租户读取和任务包装保留兼容入口，并明确结构性兼容限制。
22. **US22 — 有证据的支持声明。** 作为脚手架使用方，我能看到各场景的接入示例、生命周期禁用规则、真实运行测试和未支持项；依赖可引入或测试计划存在不被当成能力已支持。

## Implementation Decisions

### ID01 — 模型、信任与权限边界

- ExecutionContext 为不可变整体，包含显式 ExecutionScope、Actor、Initiator 和 Correlation；非范围字段继续遵守既有合法性与非空约束。Tenant Scope 必须持有合法非空 TenantId，Platform 不携带魔法 TenantId。
- Holder 是线程内读取入口，不是身份来源。Actor 表示本次执行身份，Initiator 保留原始审计来源而不参与当前授权；传播不重新认证、不扩大权限、不复制另一请求的身份字段。
- 普通业务 API 不为技术传播新增 context/tenant 参数；租户身份真正参与业务规则时仍可进入业务模型。Platform 不使普通租户 Repository、租户 SQL 或 tenant-only 出站边界免于严格检查。
- HTTP、消息、任务及每事件入口仍从各自可信信息建立上下文，进程内快照不是新网络协议。未经验证的 Header、模型输出、工具参数或事件 payload 不得直接成为可信 ExecutionContext。
- 接入方负责判断能否进入目标租户并构造可信目标上下文；Scope 安装只执行该决定，不实现 impersonation、审批、经销商树、授权时效或对象级数据授权。

### ID02 — 最小公共契约及兼容

以下名称沿用已确认草案作为接口意图；精确命名可在不改变可观察契约下调整。

| 公共接缝 | 必须保留的语义 |
| --- | --- |
| ExecutionScope 的 platform/tenant 构造 | 两种显式范围；tenant 不接受 null 或非法 TenantId |
| `Holder.current()` / `require()` | 可选查询 / 缺失立即失败；Platform 与 Tenant 都是已存在上下文 |
| `context.requireTenantId()` | 仅 Tenant 成功；Platform 明确失败 |
| `Holder.open(context)` | 安装非空可信目标；返回当前线程所属 Scope |
| `Snapshot.capture()` | 既有严格捕获；不改成默认容忍缺失 |
| `Snapshot.captureAllowingAbsent()` | 捕获存在或明确缺失，二者均为有效固定快照状态 |
| `Snapshot.of(context)` | 接收非空可信上下文；不执行授权 |
| `snapshot.openScope()` | 安装捕获状态，包括明确遮蔽现有值；退出恢复 |
| `snapshot.bind*` | 返回具名、实现对应 JDK 函数接口的固定快照绑定对象 |

保留既有以 TenantId 建立上下文的构造器、`initiatedBy(...)`、严格 `tenantId()` 读取别名以及 Holder 的 run/call 和 Snapshot 的 Runnable/Callable wrap 兼容入口；统一委托同一恢复机制。旧租户读取不能在 Platform 上返回 null。

保留常规调用入口不等于 record component、解构、反射、序列化形状或所有二进制消费者完全兼容。实施时检查实际消费者并记录必要迁移，不把 ExecutionContext 发布成新的网络 DTO。Callable 私有异常包装的移除与原来接受 null 的收紧需明确记录为行为修正。

### ID03 — 一个 ThreadLocal 恢复内核

- 首期保留 ThreadLocal 与 imperative Scope，不迁移 ScopedValue，不引入可替换载体框架，也不声称这一选择有已测得的性能优势。
- 每次打开 Scope 有独立绑定身份，不能只用当前 context 对象相等或同一性判断逆序；存在与缺失状态均服从嵌套恢复。
- 未关闭 Scope 仅所属线程可关闭；错误线程或错误次序的关闭失败且不修改当前状态。合法关闭后的重复关闭保持幂等。
- `open(null)`、`of(null)` 拒绝输入；允许缺失捕获是公开的独立契约，不以 null 或含糊布尔参数充当平台/清理开关。
- 明确缺失执行期间隐藏目标线程的原值；退出时恢复原值，原来缺失则移除绑定。inline execution 与线程池执行一视同仁。
- 每次实际同步调用以统一 Scope 恢复并关闭；正常返回、RuntimeException、Error 和 Callable 的 checked Exception 均恢复。上下文包装不改变原结果或业务异常；Executor/Future 原生规定的异常封装不属于新增私有包装。
- 不让一个可关闭 Scope 横跨多个线程、Servlet dispatch 或 Publisher/Future 的完整生命周期。取消、超时、父 Scope 退出不等于实际执行线程已经退出；仍在执行的回调在真正返回/抛出时清理。

### ID04 — 绑定对象与执行器

- 绑定方法覆盖 Runnable、Callable、Supplier、Function、Consumer、BiFunction、BiConsumer，返回对应具名 Bound 类型；不扩展为任意函数接口生成框架。
- 固定快照属于捕获时的逻辑执行，不得缓存到跨请求静态字段、单例共享状态或按 Tenant ID 复用。相同 Tenant 但不同 Actor/Initiator/Correlation 仍是不同执行；同次执行的重复回调或重试允许复用。
- Javadoc、返回类型和用法示例必须显式表达上述归属；不承诺运行时能发现所有类型擦除或缓存误用，也不把执行线程上下文与快照不同判定为非法。
- 普通业务函数/包装器实现可复用；每次逻辑执行的身份关联需要独立保存，不承诺零分配。避免在每次委托调用内部额外创建一层捕获 lambda。
- JDK 标准接缝为显式装饰 Executor 与 ExecutorService；Spring 提供复用同一快照机制的 TaskDecorator，作用于选定 TaskExecutor。每次直接提交采用允许缺失捕获，禁止在创建 Executor/Bean 时捕获请求身份。
- 平台线程池、每任务一个虚拟线程及 inline/CallerRuns 使用同一规则；不依赖继承型 ThreadLocal，不全局劫持 raw Thread/commonPool。`@Async` 仅在实际经过接入执行器时获得保证。
- Future/SDK 请求回调在注册时绑定；指定回调 Executor 不能替代该规则。`thenCompose` 绑定覆盖同步回调本身，不自动覆盖其返回的另一条异步链。
- 执行器适配须保持拒绝、取消、Future、队列任务及 shutdown/close 的原有契约，不新增任务生命周期所有权。内部 FutureTask 等包装允许安全嵌套，实际业务调用以显式绑定快照为准；不反射拆解任意框架包装，不承诺恰好一层包装。
- 长期监听器不能固定注册线程的请求身份；每条事件应经可信事件入口重建，或者使用同等的按事件参数读取适配。

### ID05 — HTTP 受管范围、模式和认证映射

- App 接入配置明确选择业务 Controller/处理器范围及非业务排除项。这是新增上下文接入契约，与安全链的认证/授权配置独立；不能以 permitAll、是否携带 JWT 或 tenant 推导是否受管。
- 受管处理器按方法声明 > 类声明 > 默认 TENANT 解析；声明以 `@ExecutionBoundary(TENANT/PLATFORM)` 表达接口意图。缺少声明不触发全量扫描后的启动失败。
- 未选中的处理器不由该能力建立业务 ExecutionContext。排除不绕过认证、授权或租户资源检查；其他可信入口如需上下文应自行建立，不能因排除就推断 Platform。
- 认证映射先验证身份并形成可信 Actor/Initiator 与可选租户断言，再结合已解析的处理器模式建立 ExecutionContext。不能保留旧过滤器一律强制 tenant，再在 Controller 中尝试修正范围。
- 无 tenant 断言的 USER 可以是合法已认证主体；进入 TENANT 入口仍拒绝，不能降级到 PLATFORM。TENANT 保留可信租户解析及断言/目标冲突拒绝；SERVICE 指定目标租户也不自动获得该租户权限。
- PLATFORM 不因客户端带 Tenant-Id 改变范围，不建立新的 Header 模拟租户协议。Platform 入口仍执行适用权限检查。
- 缺失/无效认证在受保护入口保持 401；已认证主体不满足 TENANT 范围要求为 403；下游编码缺陷导致的缺上下文不一律伪装成 401。公共错误形状复用既有 HTTP Problem 接缝，不另造响应信封。
- 在实际用例执行之前完成模式解析与上下文安装；认证/授权仍由安全接缝承担，不能仅交给 MVC 拦截器。明确安全过滤器、处理器解析、错误处理及同步/async/error dispatch 顺序；Scope 按实际线程关闭。必要 dispatch 生命周期安全纳入验证，不承诺完整 MVC 异步返回类型自动传播。

### ID06 — Reactor 场景模板

- 提供 Mono/Flux 的可信上下文写入、调用时严格捕获传播、ContextView 严格读取及同步映射回调恢复接缝；对应草案的 withContext、propagate、require、mapInContext。
- 显式写入与捕获传播为按来源选择的两种入口，不需叠加。传播模板在被调用时捕获 Holder，不到任意订阅线程上延迟猜测身份。
- 按订阅读取原生 Context；同步映射在每次真正调用委托期间恢复 Holder。没有受管键时使用明确空快照隔离；业务 require/requireTenantId 仍失败，不回退工作线程。键存在但类型不合法时拒绝而不是视为可用身份。
- 只返回 Publisher 的同步函数不等于后续信号已经获得 Holder；阻塞工作仍需正确安排执行器，传播不负责自动识别 I/O 或把阻塞调用搬线程。
- 无固定请求身份的订阅模板可共享；绑定固定身份的 Publisher 不得跨请求缓存复用。cache/share 的业务数据共享仍需独立租户隔离证明。
- 不启用默认全局 Hook，不承诺任意 map/flatMap、任意第三方内部代码或脱离订阅的异步工作自动恢复 Holder。

### ID07 — Spring AI 单次请求、工具循环与响应

- 首期包含三类接缝：单次请求上下文注入、ToolCallback 装饰、ChatClientResponse 同步处理适配。使用 Spring AI 原生请求 context 与 ToolContext，不向普通业务接口增加第二套上下文参数。
- 每次 AI 请求捕获同一个可信 ExecutionContext，分别放入 Advisor request context 与工具调用选项对应的 ToolContext。保留其他上下文键和调用配置；保留键已绑定不同上下文时拒绝误复用，不静默覆盖另一请求身份。
- 禁止把请求快照写入共享 ChatClient 的默认租户状态；上下文不进入 prompt、工具参数 schema、模型输入或工具结果。
- ToolCallback 装饰器每次从传入的本次 ToolContext 读取；缺失、错误类型或无上下文调用入口均在执行业务委托前失败，不回退 Holder。仅在实际同步委托期间打开 Scope；保留工具定义、元数据、schema、结果和异常契约。
- 响应处理保留 ChatClientResponse，直至完成依赖其上下文的步骤；仅剩内容字符串时不承诺能恢复原生响应 context。响应适配器每次严格读取当前响应保留键，在实际同步处理期间恢复 Holder，正常或异常退出后恢复原状态。
- 上述 Tool/响应适配器按本次参数读取而非固定请求快照，允许在委托也满足线程安全和复用契约时共享；与不得跨请求共享的 Snapshot.Bound 对象明确区分。
- 自定义 Advisor 的异步回调仍须显式传播；在返回 nextStream 的外层打开 Scope 不能覆盖整条后续流，不把本规格解释成所有内部 Advisor 全透明接入。
- AI 类型与依赖留在 AI 适配边界；不要求协议无关 Service API 引入 ChatClientResponse。适配代码按实际业务语义提取输入，不为技术传播复制业务 DTO。

### ID08 — 首期范围及物理边界

| 场景 | 本规格承诺 | 不随之扩张的内容 |
| --- | --- | --- |
| Kernel | 模型、严格/允许缺失/显式快照、Scope、具名绑定及兼容入口 | ScopedValue、任意 ThreadLocal 复制 |
| JDK/Spring 执行 | 标准执行器、虚拟线程、inline 及标准回调接入 | 任意第三方 SDK/原始线程的全透明自动传播 |
| Servlet | 受管入口、TENANT/PLATFORM、认证解耦、实际执行线程与必要 dispatch 清理 | 完整 MVC 异步类型自动接入、认证产品 |
| Reactor/Spring AI | ID06、ID07 的局部模板与原生 context 适配 | 全局 Hook、任意 Advisor 自动恢复、Agent 业务 |
| 现有消息 | tenant-only wire、消费方 Actor、可信 Tenant 重建与模型兼容 | 平台消息、可空 tenantid、wire 权限或新签名/信任协议 |
| 现有 Job/Lock | 显式 context 接缝的必要兼容验证 | frozen 产品扩展；GLOBAL 锁范围不是 Platform 执行范围 |

JDK-only 能力留在 Kernel 的 context 包；Spring、Reactor、Spring AI 依赖在各自适配边界实现，不能拖入所有 Kernel 消费者。精确 Maven artifact 名称由实际依赖与独立消费者证据确定；不得为每个 Bound 类型机械拆模块。

本次使用自有 Scope/Snapshot 保持一致语义；Micrometer 不作为核心必选或全局注册机制。MDC、Trace、Spring SecurityContext 与业务上下文保持分离，本次不自动传播；[受治理可观测性规格](../governed-observability/spec.md)在适配层组合 OTel 与业务快照，不能改变本规格的身份、缺失值、捕获时点和恢复契约，Kernel 不引入 OTel。Correlation 继续由 ExecutionContext 承载，公网新根生成、内部与因果派生工作继承，原有非空/合法性约束不变；HTTP 认证前使用独立诊断状态，不伪造业务身份，也不顺带实施本规格全部 Scope/Platform/AI 能力。

### ID09 — ADR 与领域文档一致性

本轮只写规格，不把权威领域文档、ADR 或产品支持状态静默改成已交付；实施交付需同步以下明确修订：

- [CONTEXT.md](../../CONTEXT.md)：补充 Execution Context、Execution Scope、Platform/Tenant 与快照归属；细化现有 Tenant Context 定义，保持普通业务接口租户透明及租户资源严格隔离。
- [ADR 0003](../../docs/adr/0003-make-tenant-context-a-system-boundary.md)：把“一律建立 Tenant Context”细化为受管入口按显式/默认范围建立可信 ExecutionContext，租户入口/资源要求具体 Tenant。保留 null、魔法租户、通用 ignore-tenant 禁令及跨租户管理独立授权要求；现有消息 wire 不扩张。
- [ADR 0035](../../docs/adr/0035-expose-authentication-and-authorization-seams-without-owning-iam.md)：明确 Platform/Tenant 是执行范围，不是平台管理员、经销商层级或授权产品。
- 用一个新的传播 ADR 记录捕获时点、Scope、空快照、绑定生命周期及局部 Reactor/AI 决策；编号在实际写入时按当前序列分配，不预占。
- 保持 [ADR 0004](../../docs/adr/0004-use-one-service-api-for-local-and-remote-calls.md)、[ADR 0021](../../docs/adr/0021-validate-inbound-message-contracts-without-wire-permissions.md)、[ADR 0031](../../docs/adr/0031-map-at-adapters-only-for-semantic-differences.md) 的 Service API、消息身份和模型转换边界；遵守 [ADR 0018](../../docs/adr/0018-group-lightweight-contracts-in-moduvera-kernel.md) 的 Kernel 依赖边界和 [ADR 0009](../../docs/adr/0009-use-explicit-local-transaction-work-units.md) 的事务边界。上下文快照不传播事务或数据库连接。

## Testing Decisions

以下接缝已随草案确认；以公开调用及实际运行边界验证，不为了测试方便新增生产业务端点。所有测试均为交付要求，不是本轮已运行或通过的证据。

| 编号 / 对应故事 | 最高稳定可观察接缝 | 必须证明的行为 |
| --- | --- | --- |
| T01 / US01–07 | 现有 [HolderTest](../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextHolderTest.java)、[SnapshotTest](../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshotTest.java) 的公开调用路径 | Platform/Tenant/缺失分层读取；null 拒绝；同对象/不同对象嵌套、错误次序及跨线程关闭；合法重复关闭；空快照遮蔽并恢复；值及 RuntimeException/Error/checked Exception 透明 |
| T02 / US04、08、11 | 真实 JDK Executor/ExecutorService、虚拟线程及 Spring TaskExecutor | 单工作线程交错身份且复用后无残留；提交时捕获；inline/CallerRuns；任务开始前取消、运行中取消/超时、拒绝及真正退出后恢复；Future 与 shutdown/close 契约 |
| T03 / US03、09–11 | 真实 CompletionStage 及标准 SDK 形状的回调注册/触发 | 已完成 Future 的 inline 回调、外部线程完成与异步调度；七类函数形状；父 Scope 已退出、同次重试、显式绑定再经执行器装饰；thenCompose 新异步边界不误宣称覆盖 |
| T04 / US12–14 | 真实 Servlet 安全链、受管处理器选择、Controller 与签名 JWT | 平台线程/虚拟线程；默认及类/方法覆盖；无 tenant USER 在 PLATFORM 成功、TENANT 为 403；无效认证为 401；非业务排除入口带有效无 tenant JWT 不误触发 Tenant 要求；Header/断言冲突、平台不被 Header 切换、错误与必要 dispatch 清理 |
| T05 / US15–16 | 真实 Reactor 订阅、操作符及调度器 | 调用时捕获与按订阅读取；交错上下文、缺失/错误键、正常/异常/重试/取消；实际同步回调内部 Holder 与退出后原线程状态；不借用线程偶然值 |
| T06 / US17–19 | 真实 Spring AI ChatClient、ToolCallingAdvisor、ToolCallback 和响应链 | 测试专用脚本化 ChatModel 驱动至少两轮工具调用；换线程/并发请求后 Tool 与响应内部身份正确；缺失/错误类型/冲突键拒绝；配置和工具元数据保留；共享读取适配器不固定请求身份；退出恢复 |
| T07 / US02、14、20 | 生产租户数据、出站/入站消息 Adapter 与真实基础设施 | Platform 不能使租户 SQL/消息变为无过滤或空租户；既有 Tenant 业务、Outbox/Inbox、可信 envelope 与消费方 Actor 兼容；负向调用无业务/消息副作用；既有 Job/Lock 公共语义不扩张 |
| T08 / US21 | 真实 Maven 消费者与既有架构/依赖检查 | 旧调用入口兼容；实际 record/序列化消费者迁移可见；Kernel 不依赖 Spring/Reactor/AI；不使用可选适配的消费者不被迫引入框架依赖 |
| T09 / US10、22 | 独立消费者通过公共接缝运行，接入说明与生命周期示例 | 每项新增支持声明都有消费者证据；固定快照禁跨请求、无状态读取可共享、三种用法按边界选择及未支持项明确，不以 POM/单测存在宣称全场景支持 |

### 验证方法与负向语义

- 每个传播场景同时断言 ExecutionScope、Actor、Initiator、Correlation；包括不同租户以及同租户不同请求，不能只断言 tenant 相同。
- 对取消/超时使用有界、可控制的同步协调，区分通知时点与任务真实退出；不在完成监听器里假装清理另一线程，也不以固定 sleep 证明无残留。
- 复用已有最高接缝并首先补当前 Scope/Callable 行为回归；然后扩展到实际执行器、HTTP 和框架链。按 [交付标准](../../docs/agents/delivery-standards.md) 做风险相称验证，不在本规格中启动 TDD 或实施流程。
- 按 [ADR 0034](../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md) 通过生产运行 Adapter 与真实 PostgreSQL/MySQL/Kafka 等适用基础设施证明隔离、事务和可靠消息；不以测试内存替身声称这些语义等价。
- 脚本化 ChatModel 仅在测试源码中控制框架调用与响应时序，不证明真实模型行为或外部供应商兼容；框架上下文契约测试不依赖付费模型调用，不新增生产测试替身。
- HTTP 或消息适配变更继续履行现有公共契约、适用 Local/Remote 及双拓扑 Scenario 回归义务；不为 Platform 新造业务服务，也不把每种新模板扩大成所有基础设施组合的笛卡尔积。
- 新公共能力通过 [产品支持面](../../docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md) 要求的独立消费者证据后才能提升状态；规格、测试计划或源码存在都不构成该证据。

## Out of Scope

- 用户/租户目录、认证产品、RBAC/经销商/组织层级、License、对象级数据范围、审批、临时授权流程以及自动 impersonation。
- 通用忽略租户开关、魔法租户、把缺失解释成 Platform、把 Platform 当作全局权限，或通过客户端 Header/模型参数直接授权切换。
- 平台消息协议、可空消息 tenantid、新 wire 权限/签名协议、额外同步 Service API、Job/Lock frozen 产品扩展。
- 默认全局 Reactor Hook、任意 Advisor/SDK/Thread 自动透明传播、复制所有 ThreadLocal、ScopedValue 迁移、StructuredTaskScope 或 parallelStream 的独立自动适配。
- 完整 MVC 异步返回类型自动接入。首期仍需证明所覆盖入口与必要 dispatch 的安全生命周期；不得以此排除项放弃线程清理。
- MDC/Trace/Spring SecurityContext 自动传播、可观测性产品、事务/连接传播、cache/share 的业务数据隔离方案。
- 无证据的零分配/性能提升承诺、通用函数包装器生成框架、仅为组织类名新增 Maven artifact。
- 本轮的实现、拆票、权威文档/ADR 正式写入、支持状态提升、提交、发布和部署。

## Further Notes

### 未决事项与实施证据责任

没有待用户重新决定的产品或架构分歧。下列事项属于已确认范围内的实施选择与证据责任，不是无限扩张授权：

| 事项 | 责任方与必须提交的证据 | 越界处理 |
| --- | --- | --- |
| HTTP selector 的具体类型及处理器/安全/dispatch 顺序 | 实施者给出实际接入方式与 T04；保留入口选择独立于认证授权 | 若无法满足已确认入口语义，回到 Grill，不以强制全量扫描或降级 Platform 规避 |
| Spring AI/Reactor 依赖和物理 artifact | 实施者锁定与仓库 Java/Boot 基线兼容的依赖，给出 T05/T06/T08/T09 | 不把先前源码研究版本当成运行资格；必要的公共契约变化需重新确认 |
| ExecutorService 委托及 Future/关闭语义 | 实施者给出 T02/T03，证明嵌套包装、取消和恢复边界 | 不静默缩成只支持某一种 Executor，也不承诺任意内部异步工作透明覆盖 |
| 支持声明与权威文档同步 | 实施者/后续验收者提供 ID09 的文档更新及对应运行/消费者证据 | 保持 Planned，直到达到既有支持提升条件；不提前关闭 finding |

具体类名、方法名与 artifact 名称可在这些契约内确定，不需逐个名称再次 Grill。若测试或依赖约束暴露必须改变公共能力边界，则提出具体证据和一项整体决策，不静默扩大或削减承诺。

### 来源与相邻工作

- 设计来源：[已确认接口草案](interface-draft.md)，其中保留 D01–D12、方案排除、当前代码差距与官方资料链接。外部源码研究只作为设计依据，不宣称本仓库已锁定或运行该依赖版本。
- 原始问题：[WebFlux/ThreadLocal review finding](../review-2026-09-02-scaffold-baseline/findings/08-threadlocal-context-with-webflux.md)。本次保留 Fixes 关联，不写 Fixed 或关闭 finding。
- 可观测性互操作与其他独立 HTTP/消息主题不得通过本规格顺带实施；共享接缝有并行变更时保留工作树改动，并按实际行为契约协调。
- 下一阶段仅在用户批准本规格且明确请求后进入 to-tickets；本轮停止于可审阅规格与文档验证。

## Comments

- 2026-09-05：此前早期提纲保留的载体、捕获时点、响应式接入、兼容与支持矩阵问题，已通过 Grill 收敛为接口草案。
- 2026-09-05：用户回复“确认”，整体接受草案公共接缝、测试接缝和首期边界；本次据此更新唯一 spec，未拆票、未实施、未提交，未更改权威文档或产品支持状态。
- 2026-09-05：文档一致性复核未发现重要遗漏或范围变化；必需章节、22 条故事、9 类验收接缝、本地链接与尾随空白检查通过。全库 `python3 tools/tracker/check.py` 返回 1，唯一报告为未修改的 `.scratch/http-problem-contract/issues/01-unify-external-problem-details.md` 缺少 `Blocked by` 元数据；未把该结果记为全库通过，未运行功能测试。
- 2026-09-06：补充与可观测性规格的适配层组合及 correlation 分工，保留当前状态与范围；HTTP 既有票本次补齐 Blocked by，前条记录仍表示当时结果，不改写历史验证。
