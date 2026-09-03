Type: finding
Status: confirmed
Severity: major
Area: framework
Claim: 基础 ExecutionContext 能力缺少覆盖 WebFlux/Reactor、Servlet/虚拟线程和显式异步执行的统一传播契约；当前 ThreadLocal Holder 与手工 Snapshot 只证明部分 imperative 场景，不能构成跨执行模型支持。
Evidence: ExecutionContextHolder 使用 ThreadLocal，ExecutionContextSnapshot 只提供显式 Runnable/Callable 包装；Servlet 资源服务与虚拟线程测试证明同一请求执行及显式 snapshot 场景，未见 Reactor Context bridge、统一 context accessor 或跨 scheduler 支持。gateway-app 当前通过 exchange/Reactor 参数显式传递 correlation 和 session，因而没有现存故障，但也没有建立 trusted ExecutionContext。
Verification: 读取 ExecutionContextHolder、ExecutionContextSnapshot、ExecutionContextFilter、CorrelationGlobalFilter 与 OrderSessionGatewayFilter；枚举 Servlet、WebFlux、消息、任务、executor 和虚拟线程入口的建立、传播与清理路径，并用跨 Reactor scheduler、虚拟线程及复用线程的隔离测试验证最终契约。
Planned: .scratch/execution-context-propagation/spec.md

# Reactive 业务 Adapter 缺少上下文 bridge

## Verdict

扩展后的 Claim 确认成立。当前没有已证实的 Gateway 运行时上下文丢失，但维护者要求把 ExecutionContext 传播提升为基础能力，而不只修补本 review 所见的 WebFlux 缺口。能力必须同时支持 WebFlux/Reactor 与虚拟线程，并对显式异步执行、线程复用、失败清理和 fail-closed 行为给出统一契约。

该能力与日志/Trace context 需要互操作，但 trusted business ExecutionContext、observability context 和跨网络身份声明仍是不同边界。具体 carrier、自动/显式传播策略和兼容方式放入独立 topic 设计；在权衡完成前不预先固定 ThreadLocal、ScopedValue、Reactor Context 或第三方 context-propagation 实现。
