# ExecutionContext 最小接口与场景契约草案

日期：2026-09-05

文档性质：Grill 收口附件；用户已于 2026-09-05 整体确认接口、测试接缝与首期边界。本文保留草案时的提案措辞与示例，不是实施授权。

当前规格：[spec.md](spec.md) 已据确认结果收敛，是本能力唯一正式 tracker spec；本文作为决策来源，不维护第二份并行规格。

说明：下文 Java 是接口意图示例，不是已存在或已编译的实现。标为“建议”的接口、兼容方式和首期支持边界需随本草案整体确认。

## 1. 已确认的决策清单

| 编号 | 已确认决定 | 边界 |
| --- | --- | --- |
| D01 | 需要上下文的业务与基础设施统一通过 ExecutionContextHolder 读取 | 普通业务接口不为技术传播新增 context/tenant 参数 |
| D02 | ExecutionContext 表达 Platform 或 Tenant(id) | 上下文缺失是另一种状态，不等于 Platform |
| D03 | Platform 是未绑定单个租户的执行范围，不是全局权限 | 经销商层级、资源归属、审批、临时授权规则由接入方解释 |
| D04 | 支持安装可信目标上下文、临时切换和自动恢复 | 切换本身不授予权限，不实现模拟用户或 IAM 业务流程 |
| D05 | 已接入能力的业务 HTTP 入口默认 TENANT，平台入口显式声明 | 方法声明优先于类声明；类声明优先于默认值 |
| D06 | 不做漏声明导致启动失败的全量强校验 | 非业务入口按接入配置处理；检查排除不等于绕过认证授权 |
| D07 | 通用适配器有则传播、无则隔离；严格捕获保留 | 空快照执行期间遮蔽目标线程的原上下文，不借用残留值 |
| D08 | 直接任务在提交时捕获；请求归属的 Future/SDK 回调在注册时绑定 | 不依赖回调由哪个线程触发；长期监听按每条事件建立上下文 |
| D09 | 快照固定，父 Scope 退出不自动取消已提交任务 | 子任务上下文不回写父任务；取消/超时不等于实际退出 |
| D10 | 普通函数可复用，固定快照绑定对象不得跨逻辑执行混用 | 相同租户、不同用户/请求也不可混用；同次执行的重试可复用 |
| D11 | Reactor 采用场景化模板，Spring AI 使用原生请求/工具上下文通道 | 不承诺任意操作符、任意内部 Advisor 都自动具有 Holder |
| D12 | 不建设仅覆盖一种 Executor 的局部方案 | 统一语义，各场景有适合的标准接入方式，不强制三种方式叠加 |

已排除：以 Tenant ID 是否为空猜测平台身份、魔法租户、通用 ignore-tenant 开关、复制任意 ThreadLocal、默认全局 Reactor Hook、将请求绑定对象作为跨请求缓存、通过取消回调提前清理另一线程。

尚未实现或验证：本草案中的新公共接口、Platform 全链路适配、新传播适配器及其运行证据。对话确认不等于已支持。

## 2. 核心模型与兼容建议

```java
// 形状示意：Platform/Tenant 可作为 ExecutionScope 的嵌套实现。
sealed interface ExecutionScope {
    // Platform：无当前租户；Tenant：必须持有合法非空 TenantId。
    static ExecutionScope platform();
    static ExecutionScope tenant(TenantId tenantId);
}

record ExecutionContext(
        ExecutionScope scope, Actor actor,
        Initiator initiator, String correlationId) {
    TenantId requireTenantId();
}
```

- `scope/actor/initiator/correlationId` 仍须合法、非空；快照保存不可变上下文，不保存请求对象、数据库连接或事务。
- `Holder.require()` 在 Platform/Tenant 均成功；`context.requireTenantId()` 仅在 Tenant 成功。Platform 访问普通租户数据接缝时明确失败，不退化成无过滤查询。
- 建议保留旧 `ExecutionContext(TenantId, Actor, Initiator, String)` 构造器、`initiatedBy(...)` 和 `tenantId()`；它们分别建立 Tenant 或作为严格租户读取别名。新代码使用 `scope()` / `requireTenantId()`。
- 这只是常规调用兼容建议，不宣称 record component、解构、反射或 JSON 形状完全兼容。ExecutionContext 不作为新的网络 DTO 发布；实施时检查所有实际消费者。

## 3. 一个恢复内核，显式区分两种捕获

建议首期保留 ThreadLocal 作为 Holder 内部载体，复用现有 imperative Scope；本次不迁移 ScopedValue。该选择基于现有兼容形态，不声称 ThreadLocal 性能优于 ScopedValue。Kernel 保持无 Spring/Reactor/Micrometer 依赖。

| 接口意图 | 契约 |
| --- | --- |
| `ExecutionContextHolder.current()` | 查询当前上下文；缺失返回空 |
| `ExecutionContextHolder.require()` | 要求已有上下文；缺失抛既有缺失异常 |
| `ExecutionContextHolder.open(context)` | 安装非空目标上下文；返回限当前线程、按逆序关闭的 Scope |
| `ExecutionContextSnapshot.capture()` | 保留既有严格语义；捕获时缺失立即失败 |
| `ExecutionContextSnapshot.captureAllowingAbsent()` | 明确允许捕获“缺失”状态；通用传播适配器使用 |
| `ExecutionContextSnapshot.of(context)` | 接收非空可信上下文；不自行认证或授予权限 |
| `snapshot.openScope()` | 安装捕获状态，包括明确清空；关闭时恢复原状态 |

名称为建议；严格/允许缺失的区别必须在 API 名称与 Javadoc 中可见，不藏在一个含糊的布尔参数里。

恢复不变量：

1. `open(null)` / `of(null)` 拒绝输入；空快照走明确的恢复路径，不以 null 充当平台身份或通用清理入口。
2. 每次打开 Scope 有独立绑定身份。即使两层安装同一个 ExecutionContext 对象，也必须严格逆序关闭。
3. 未关闭 Scope 只能由所属线程关闭；错误关闭不改变当前状态。合法关闭后的重复关闭保持幂等。
4. 业务回调正常返回、抛 RuntimeException/Error/checked Exception 时均恢复；Callable 的原受检异常不再额外包装为项目私有 RuntimeException。
5. 缺失快照运行在原本有上下文的线程上时，回调内部必须看不到原值，回调退出后再恢复原值。该规则也适用于 inline execution / CallerRuns。
6. 每次实际同步回调有自己的 Scope；不把 Scope 的关闭挂在整条 Flux/Future 的完成通知上。

## 4. 任务与回调绑定：类型和生命周期必须可见

建议返回具名绑定类型，例如 `ExecutionContextSnapshot.BoundFunction<T, R>`，同时实现对应 JDK 接口，使其能直接传给 Executor/Future/SDK。构造只经 Snapshot 的绑定方法完成。

| 绑定方法意图 | 兼容的 JDK 接口 | 常见用途 |
| --- | --- | --- |
| `bindRunnable` | Runnable | execute、无返回任务 |
| `bindCallable` | Callable | submit、有返回任务及受检异常 |
| `bindSupplier` | Supplier | supplyAsync 等提供值的回调 |
| `bindFunction` | Function | thenApply、thenCompose 的同步回调部分 |
| `bindConsumer` | Consumer | SDK 回调、thenAccept |
| `bindBiFunction` | BiFunction | handle、双输入组合回调 |
| `bindBiConsumer` | BiConsumer | whenComplete 等完成观察回调 |

这组方法覆盖标准任务与常见 CompletionStage 形状，不扩展为任意函数接口生成框架。现有 `wrap(Runnable/Callable)` 建议保留为兼容入口，委托同一恢复机制。

```java
// 可以跨请求复用：只在执行时读取 Holder，不保存某次请求快照。
private final Function<Response, Result> processor = this::process;

CompletableFuture<Result> register(CompletableFuture<Response> future) {
    var snapshot = ExecutionContextSnapshot.capture();
    var bound = snapshot.bindFunction(processor);
    return future.thenApply(bound);
}
```

- `snapshot` 与 `bound` 属于注册时那次逻辑执行；禁止放入跨请求静态字段、单例共享状态或缓存。
- 同次执行可以排队、重试或多次回调；不是一次性对象，也不在外层 Scope 结束时失效。
- `thenCompose` 的绑定只覆盖回调本身；其返回的另一异步链，仍应按实际新边界传播，不能把“返回 Future”当作整个异步工作都被 Scope 包住。
- 具名类型便于 IDE、文档和针对性检查，不是语言层面的缓存禁止机制。调用方可把类型擦成 Function，运行时也不能仅因执行线程上下文不同就判断误用——这正是合法跨线程回调的常态。
- 每次逻辑执行需要保存上下文关联；不承诺零分配。实现应避免在每次回调内部再为调用委托创建一层捕获 lambda；用同一 Scope 核直接调用原函数。

## 5. 开发者三种用法与执行器适配

以下是不同边界的用法，不要求同一个边界重复手写多层包装。

```java
// A. 配置好传播装饰的执行器：提交时捕获，允许来源缺失。
executor.execute(service::execute);

// B. 请求归属的回调：在注册时显式严格绑定。
var snapshot = ExecutionContextSnapshot.capture();
future.thenApply(snapshot.bindFunction(processor));

// C. 入口或临时切换：接入方给出可信目标上下文。
try (var ignored = ExecutionContextHolder.open(targetContext)) {
    service.execute();
}
```

- JDK 层建议提供显式 `ContextExecutors.propagating(Executor)` 与 `propagating(ExecutorService)` 适配；平台线程池与每任务一个虚拟线程执行器复用相同传播语义。
- Spring 层建议提供一个 TaskDecorator，复用 `captureAllowingAbsent()` 与绑定能力，配置到选定 TaskExecutor；`@Async` 只有实际走该执行器时才获得该保证。
- 适配器本身可复用，不能在创建 Executor/Bean 时捕获请求上下文。每次直接提交独立捕获。
- 不全局劫持 raw Thread/commonPool。手工线程可显式传入绑定任务；Future 即使使用指定 Executor，仍不能替代注册时绑定后续回调。
- 原执行器的拒绝、Future 取消、shutdown/close、队列任务和异常语义需保留并验证。上下文适配不新增任务生命周期所有权。
- 框架内部可能存在 FutureTask 等嵌套包装：允许安全嵌套，实际业务回调以其显式绑定快照为准。不反射拆解任意框架包装，也不承诺每次任务恰好只有一个包装对象。

## 6. HTTP：范围声明与认证映射必须分层

建议声明名使用 `@ExecutionBoundary`，避免与核心 `ExecutionScope` 类型重名；注解值只有 `TENANT/PLATFORM`。名称随草案整体确认。

```java
@ExecutionBoundary(TENANT)
class OrderController { /* 类内方法继承；不写时同样默认 TENANT */ }

@ExecutionBoundary(PLATFORM)
class PlatformController { /* 平台入口仍受认证、授权约束 */ }
```

- 受管范围是新增接入契约：由 App 配置明确选择业务 Controller/处理器范围及非业务排除项；不能从 `permitAll`、是否携带 JWT 或 tenant 推导。具体 selector 类型名留到 spec。
- 选中后的模式解析：方法声明 > 类声明 > 默认 TENANT。不做全量漏标启动失败。未选中的处理器不由此能力建立业务 ExecutionContext；排除只影响上下文接入，不跳过独立认证、授权或数据隔离，不以 PLATFORM 充当匿名许可。
- 模式是入口契约，不根据角色、Tenant-Id 有无、JWT 是否包含 tenant 来猜测。
- 认证映射先得到可信 Actor/Initiator 和可选的租户断言；随后结合入口模式建立 ExecutionContext。无 tenant 的 USER 可以是合法已认证主体，但进入 TENANT 入口必须被拒绝，不能降级到 PLATFORM。
- TENANT 保留可信租户解析、断言与目标不匹配拒绝规则；SERVICE 提供目标租户并不自动获得该租户授权。
- PLATFORM 不因客户端携带 Tenant-Id 自动切换范围；没有新的“通过 Header 临时取得租户身份”协议。
- HTTP 集成必须在处理器模式已解析、用例尚未执行的边界完成安装；原过滤器先强制租户、再由 Controller 注解修正不可行。
- 不能只靠 MVC 拦截器承担认证/授权。需证明端点模式解析、安全过滤器、错误处理及同步/async/error dispatch 的正确次序；Scope 按实际执行线程/回调关闭，不跨 dispatch 保存一个可关闭对象。
- 建议错误语义：无效/缺失认证继续 401；已认证主体不满足 TENANT 入口的范围要求按入口访问拒绝 403；真实编码缺陷导致下游缺上下文，不一律伪装成认证失败。公共错误形状沿用既有 HTTP Problem 接缝。

## 7. Reactor 与 Spring AI：载体原生，Holder 按回调恢复

### 7.1 Reactor 场景模板

建议提供一个小的 Reactor 适配表面：

| 接口意图 | 作用 |
| --- | --- |
| `withContext(trustedContext, mono/flux)` | 显式把可信上下文写入订阅 Context；返回对象按该次执行使用 |
| `propagate(mono/flux)` | 在调用本方法时严格捕获 Holder，随后绑定；不等到任意订阅线程才读取 Holder |
| `require(contextView)` | 从 Reactor Context 严格读取，不回退到线程偶然值 |
| `mapInContext(source, synchronousFunction)` | 每个订阅读取自己的 Context，在实际同步回调期间用统一 Scope 恢复 Holder |

`mapInContext` 在没有该 Context 键时采用明确的空快照隔离，业务要求 context/tenant 时仍失败。`propagate`/`withContext` 是两种写入方式，按来源选择，不必叠加。

```java
// 由同一可信入口提供 context。源中的阻塞调用仍需正确选择执行器。
Flux<Result> result = ReactorExecutionContexts.withContext(
        context,
        ReactorExecutionContexts.mapInContext(source, processor));
```

- 同步业务回调只读 Holder；只返回 Publisher 的同步方法并不代表其后续信号已经恢复 Holder。
- 不默认启用 Reactor 全局 Hook；不承诺任意 map/flatMap、脱离订阅的异步工作或第三方内部代码都自动具有 Holder。
- 可复用的是不固定请求身份、按订读取 Context 的逻辑模板；不得跨请求缓存绑定了固定身份的 Publisher。
- 传播不自动隔离 `cache/share` 等共享的业务数据；这类数据共享仍有自身的租户边界，不能用 Context 正确性替代数据隔离证明。

### 7.2 Spring AI 最小适配

1. 每次 AI 请求捕获一次可信 ExecutionContext，分别写入 Advisor request context 与 ToolContext 对应的调用选项；保留其他参数，保留键已绑定不同上下文时拒绝误复用。
2. 统一装饰 ToolCallback：每次实际调用从本次 ToolContext 读取上下文，再打开 Scope 调用委托。缺失/类型不符必须失败，不回退到当前线程身份。
3. 委托原 Tool 定义、参数 schema、元数据与结果语义；上下文不进入 prompt、模型参数或工具结果。
4. 返回流保留 `ChatClientResponse` 到完成需要上下文的处理步骤；调用 `.content()` 得到 String 后，不能再从字符串恢复 Advisor context。
5. 首期提供“从当前 ChatClientResponse 读取上下文”的响应处理适配器，使处理函数仍只读 Holder。每次读取当前响应的保留键；缺失/类型不符失败，不回退到线程偶然身份。仅在实际同步处理期间打开 Scope，正常返回或异常均恢复原线程状态，不在适配器创建时捕获请求身份。

```java
// 接口意图：无固定请求身份，可放在 AI 接入组件中复用。
Function<ChatClientResponse, Result> responseMapper =
        SpringAiExecutionContexts.responseMapper(this::processResponse);
// 每次请求的响应流调用 map(responseMapper)，回调内部通过 Holder 读取。
```

该接缝属于 AI 适配边界，不要求协议无关的 Service API 引入 ChatClientResponse；需要调用业务用例时，由接入代码提取业务输入。

必须区分两类对象：

| 对象 | 是否固定快照 | 能否作为共享组件 |
| --- | --- | --- |
| `Snapshot.BoundFunction` 等绑定对象 | 是 | 否，不得跨逻辑执行共享 |
| `ContextAwareToolCallback` / 从响应对象读取上下文的适配器 | 否，每次从本次参数读取 | 可在委托也满足线程安全/复用契约时共享 |

这不是让所有 Advisor 自动拥有 Holder。自定义 Advisor 的异步回调仍需使用对应绑定/读取模板；把 Scope 包在 `return nextStream(...)` 外面只能覆盖同步组装阶段。

Spring AI 2.0.1 的 ToolCallingAdvisor/native context 路径已核对源码，但本仓库还没有在该能力中锁定和运行该版本；选择依赖后须用真实 ChatClient/ToolCallingAdvisor 链和测试专用脚本化 ChatModel 驱动至少两轮工具调用来证明，不调用付费模型来替代契约测试。

## 8. 首期支持边界建议：覆盖方式不等于新增产品

| 场景 | 首期建议 | 不附带的承诺 |
| --- | --- | --- |
| Kernel 同步、临时切换、空快照 | 实现并验证 | 不迁移 ScopedValue，不复制任意线程变量 |
| JDK 线程池/虚拟线程/inline executor | 标准执行器适配及显式绑定 | 不全局接管任意线程 |
| CompletionStage/SDK | 支持上述标准回调绑定，并给出普通/已完成/外部完成的示例 | 不保证所有第三方接口、内部派生异步工作自动传播 |
| Servlet 受保护业务入口 | 默认 TENANT、显式 PLATFORM；平台/虚拟线程与必要 dispatch 清理 | 不自动补齐完整 MVC 异步返回类型或认证产品 |
| Reactor | 局部写入/读取/同步回调恢复模板 | 不做全局透明 ThreadLocal 平台 |
| Spring AI | 请求注入、ToolCallback、响应回调接入 | 不实现 Agent 业务、工具授权产品或任意 Advisor 全透明恢复 |
| 现有消息入站/出站 | 保持 tenant-only wire，重建 Tenant(id)，验证新模型兼容 | 不增加平台消息、可空 tenantid、权限字段或新信任协议 |
| 现有 Job/Lock | 使用显式 context 的既有接缝，做必要兼容验证 | 不重设计 frozen 产品；Job 的 GLOBAL 锁范围不等于 Platform 执行范围 |
| MDC/Trace/Spring SecurityContext | 明确与业务上下文分离；留有边界适配空间 | 本次不自动传播这些状态，不复制事务/连接，不宣称已完成可观测性互操作 |
| StructuredTaskScope/parallelStream | 可评估显式包装用法，独立适配暂缓 | 不承诺 preview API 集成或原生自动继承 |

物理组织建议：JDK-only 能力留在 Kernel 的 context 包；Spring、Reactor、Spring AI 依赖在相应边界实现，不能通过 Kernel 拖入所有消费者。是否需要独立 Maven artifact 应由实际依赖/消费者边界证明，不为每个包装类型拆模块。精确 artifact 名称在 spec/实施依赖检查中确定。

实现建议：先用现有自有 Scope/Snapshot 统一语义；Micrometer 不作为本次核心必选或全局注册机制。若后续接生态传播，须验证缺失值清理和调用时点，不得改变本草案契约。

## 9. 当前代码差距与验证接缝

本次只读检查了源码与测试，没有运行功能测试；下面是待修订与待验证清单。

| 当前证据 | 草案要求 |
| --- | --- |
| [Holder](../../framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContextHolder.java) 第 20、52 行：允许 null，逆序只比较 context 对象 | 拒绝隐式 null；独立栈帧校验，同对象嵌套也安全 |
| [Snapshot](../../framework/foundation/moduvera-kernel/src/main/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshot.java) 第 15、24 行：严格捕获、Callable 额外包装 checked Exception | 保留严格入口，增加空快照，统一 openScope，受检异常透明 |
| [TrustedJwtPrincipal](../../framework/starters/moduvera-auth-resource-server-autoconfigure/src/main/java/io/github/ande1922/moduvera/security/jwt/TrustedJwtPrincipal.java) 第 18 行；[Filter](../../framework/starters/moduvera-auth-resource-server-autoconfigure/src/main/java/io/github/ande1922/moduvera/security/web/ExecutionContextFilter.java) 第 39 行 | USER 认证映射与 TENANT 入口要求解耦；修改相应认证/入口测试 |
| [TenantLineHandler](../../framework/starters/moduvera-data-mybatis-plus-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/data/mybatis/ExecutionContextTenantLineHandler.java) 第 11 行 | Platform 不能走普通租户 SQL；严格租户 accessor 保持 fail-closed |
| [MessageDescriptor](../../framework/foundation/moduvera-message-core/src/main/java/io/github/ande1922/moduvera/message/MessageDescriptor.java) 第 17、31 行；[ReliableInboundEndpoint](../../framework/starters/moduvera-messaging-kafka-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/messaging/kafka/ReliableInboundEndpoint.java) 第 54 行 | 保留 tenant-only wire 与消费方 Actor，禁止以缺租户表示平台消息 |
| [JobRunner](../../framework/starters/moduvera-scheduler-spring-boot-starter/src/main/java/io/github/ande1922/moduvera/scheduler/JobRunner.java) 第 18 行 | 锁竞争范围与执行范围独立；不修改 frozen Job 模型来扩产品 |

高层可观察测试接缝建议，随草案整体确认：

- Kernel：保留 [HolderTest](../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextHolderTest.java) / [SnapshotTest](../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshotTest.java) 的公开调用路径，补 null、同对象嵌套、跨线程关闭、重复关闭、空快照遮蔽及异常透明。
- 执行器/回调：真实 JDK 单工作线程复用、虚拟线程、inline/CallerRuns、拒绝、取消前后；Future 已完成与由其他线程完成；同租户不同 Actor/correlation；父 Scope 退出后排队任务继续；绑定任务再经执行器装饰。
- HTTP：真实安全过滤器、受管入口选择、处理器模式解析及 Controller 调用；有效签名但无 tenant 的 USER 在 PLATFORM 成功、默认 TENANT 拒绝；非业务排除入口携带同类有效 JWT 时不误触发 TENANT 要求；方法/类覆盖、目标冲突、平台不被 Header 切换、401/403、执行线程及 dispatch 清理。不能只测试注解 resolver。
- Reactor：真实 Reactor 调度器与订阅；交错租户、空 Context、错误、重试、取消及不同订阅隔离；断言实际回调内部 Holder 与退出后的线程状态。
- AI：真实 Spring AI 链＋测试专用脚本化模型，至少两轮 ToolCallingAdvisor；验证 Tool/响应回调内部 Holder、其他线程完成后恢复、缺失/错误键拒绝、默认共享 Tool wrapper 不固定请求身份。
- 租户数据和消息：按 [ADR 0034](../../docs/adr/0034-verify-infrastructure-with-runtime-adapters.md) 使用生产运行 Adapter 与真实基础设施验证；模型/包装器单测不能替代 SQL、Outbox/Inbox、消息信任与隔离证据。
- 独立消费者：按 [产品支持面](../../docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md) 证明新增公共接缝真实可用后再提升支持声明；不因代码、POM 或候选测试存在就宣称支持。

## 10. Grill 文档变更建议与阶段退出条件

以下是拟更新内容，本次未直接修改权威领域文档或 ADR。

### CONTEXT.md 拟新增/细化

> Execution Context 是进入需要身份上下文的用例前建立的可信执行信息，包含 Actor、Initiator、Correlation 与 Execution Scope。Execution Scope 为 Platform 或 Tenant(id)。Platform 不代表全局权限；缺失 Execution Context 不代表 Platform。租户数据访问要求具体 Tenant Scope。快照绑定对象属于捕获时的逻辑执行，不得跨请求混用；普通处理函数不因可复用而自动获得上下文。

### ADR 0003 拟细化

> 把“所有请求、消息、任务一律必须建立 Tenant Context”细化为：各受管业务入口按已声明或默认的执行范围建立可信 Execution Context；租户入口和租户资源必须获得合法 Tenant Context 并 fail closed，显式 Platform 入口仍受身份与授权约束。Platform、上下文缺失、跨租户管理是不同语义。保留租户透明的普通业务接口，禁止 null、魔法租户与通用忽略租户开关；跨租户管理继续使用独立、显式授权的能力。现有 tenant-only 消息协议不因新模型自动扩张。

### ADR 0035 拟补充

> Platform/Tenant 是执行范围，不是角色或权限层级。平台管理员、经销商及多级组织的身份、可管理资源范围和临时授权由接入方解释；上下文切换能力不自行授予权限，不把这些业务结构加入 Kernel。

### 新传播 ADR 拟记录

记录 D01-D12、严格/允许缺失捕获、绑定生命周期、线程 Scope 恢复、入口默认策略、局部 Reactor/AI 接入、异常与取消语义、首期支持范围和依赖边界；编号在正式写入时按仓库当前序列分配。

### 进入 spec 的确认项

本草案整体确认以下新增公共接缝与收口建议后，可结束 Grill 并修订现有 `spec.md`，不再逐个方法名重开访谈：

1. 显式 ExecutionScope＋兼容调用入口；保留 ThreadLocal/Scope；Snapshot 三种来源（严格、允许缺失、显式上下文）与具名绑定类型。
2. 标准执行器/回调、HTTP 模式解析及局部 Reactor/AI 接缝，以及第 9 节的公开可观察测试接缝。
3. 第 8 节首期边界，尤其消息仍 tenant-only、Job/Lock 不扩产品、Micrometer/ScopedValue/完整 MVC 异步自动接入不作为本次默认承诺。

精确类型名、方法名和物理模块名可在不改变上述契约下于 spec/实施阶段调整。HTTP 端点模式解析与安全/dispatch 时序、Spring AI 依赖兼容、执行器 Future/关闭语义由实施者用第 9 节证据证明；若发现必须改变能力边界，则回到 Grill，不静默扩大支持范围。

## 11. 外部依据

- [JDK 26 Thread / 虚拟线程](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/Thread.html)：普通 ThreadLocal 的线程归属与虚拟线程语义。
- [JDK 26 CompletableFuture](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/concurrent/CompletableFuture.html)：回调执行策略、取消和超时边界。
- [Spring TaskDecorator](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/task/TaskDecorator.html)：任务装饰与 Future 包装异常观察限制。
- [Reactor Context](https://projectreactor.io/docs/core/release/reference/advancedFeatures/context.html) / [Context Propagation](https://projectreactor.io/docs/core/release/reference/advanced-contextPropagation.html)：订阅载体、局部恢复和自动模式的边界。
- [Spring AI Tool Context](https://docs.spring.io/spring-ai/reference/api/tools.html#_tool_context) / [ToolCallingAdvisor 2.0.1 源码](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/ToolCallingAdvisor.java)：请求/工具通道与循环；源码核验不等于本仓库运行证明。

## Comments

- 本次先完成草案及只读一致性审查，保留已有 spec 提纲和所有其他工作树改动。未实施功能，未拆票，未提交。
- 2026-09-05：用户整体确认草案，Grill 的确认条件已满足；随后更新现有 spec，本文保留为历史决策附件。后续以 spec 的契约与状态为准。
