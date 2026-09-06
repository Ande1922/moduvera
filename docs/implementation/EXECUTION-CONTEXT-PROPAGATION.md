# Execution Context propagation

Moduvera propagates one immutable `ExecutionContext` through explicitly selected
boundaries. The context contains the complete Scope, Actor, Initiator and
correlation identity for one logical execution. Absence is a separate state;
it never means Platform. Propagation carries an identity that a trusted entry
has already established and does not authenticate a caller, authorize a scope
change or grant access to a resource.

## Choose the boundary by ownership

There are three standard propagation forms. Use the one that owns the delayed
work instead of stacking them at the same boundary.

| Boundary | Capture point | Public entry |
|---|---|---|
| A task submitted directly by the current caller | Every `execute`, `submit`, `invokeAll` or `invokeAny` call | `ContextExecutors.propagating(...)`, or `ExecutionContextTaskDecorator` on a selected Spring `TaskExecutor` |
| A request-owned Future or SDK callback | Callback registration | `ExecutionContextSnapshot.capture().bindFunction(...)` and the other named `bind*` forms |
| A framework with a native context carrier | One trusted request or subscription; read again at each callback | `ReactorExecutionContexts` or `SpringAiExecutionContexts` |

A trusted synchronous entry opens a Scope only around its current call:

```java
try (var ignored = ExecutionContextHolder.open(trustedContext)) {
    return applicationService.handle(command);
}
```

For a directly submitted JDK task, decorate the executor once and reuse the
decorator. The decorator captures each submitter independently, including an
explicit absent state:

```java
ExecutorService businessTasks = ContextExecutors.propagating(rawExecutor);
Future<Result> result = businessTasks.submit(() -> applicationService.read(id));
```

Spring uses the same rule when an App installs
`ExecutionContextTaskDecorator` on a chosen `TaskExecutor`. An `@Async` method
is covered only when its proxy routes through that executor.

When a callback belongs to the request that registers it, bind that request at
registration even if the Future is already complete or another thread will
complete it later:

```java
var callback = ExecutionContextSnapshot.capture()
        .bindFunction(value -> applicationService.handle(value));
stage.thenApply(callback);
```

The executor selected for a later callback does not replace registration-time
binding. A `thenCompose` binding covers its synchronous function call only;
work represented by the returned stage needs its own boundary. A long-lived
listener must remain identity-free and rebuild a context from every trusted
event instead of retaining the identity present when the listener was
registered.

Reactor keeps the value in the subscription `Context`. Use either an explicit
trusted value or strict capture of the calling Holder, then opt only the
synchronous business callback into Holder restoration:

```java
Flux<Result> results = ReactorExecutionContexts.withContext(
        trustedContext,
        ReactorExecutionContexts.mapInContext(source, this::process));
```

`mapInContext` is stateless and may be shared. A Publisher returned by
`withContext` or `propagate` is fixed to one logical execution and must not be
cached or shared across requests.

Spring AI captures one request into the Advisor request context and native
`ToolContext`. A tool wrapper and response mapper read the native value on
every invocation, so those adapters are stateless when their delegates are
safe to share:

```java
ToolCallback tool = SpringAiExecutionContexts.toolCallback(applicationTool);
var request = SpringAiExecutionContexts.captureRequest();

Flux<Result> results = request.applyTo(chatClient.prompt().user(userText).tools(tool))
        .stream()
        .chatClientResponse()
        .map(SpringAiExecutionContexts.responseMapper(this::handleResponse));
```

Keep `ChatClientResponse` until context-dependent response work finishes. A
content string does not carry its native response context. The captured Spring
AI request object is fixed to one logical request and must not be placed in
shared `ChatClient` defaults.

## Lifecycle and failure rules

- A fixed Snapshot, bound callback, fixed Reactor Publisher or captured AI
  request belongs to one logical execution. Never cache one by Tenant ID or
  reuse it across requests, including requests in the same tenant.
- Identity-free adapters such as an executor decorator, `mapInContext`, an AI
  tool wrapper and an AI response mapper may be shared when their delegate and
  surrounding framework object are safe to share.
- A parent Scope may close after task submission or callback registration. The
  captured work remains valid and installs its context only when the delegate
  actually runs.
- Cancellation and timeout report caller-visible lifecycle events. They do
  not close a Scope on another thread. Context remains installed until the
  actual synchronous delegate exits, then the exact preceding worker state is
  restored after return or failure.
- Retrying or invoking a bound callback more than once is valid within the
  same logical execution. A new request needs a new capture.
- Strict capture fails immediately when the Holder is absent. Direct executor
  submission captures absence explicitly and hides any worker identity while
  the task runs. Native Reactor, Tool and response readers reject missing or
  wrongly typed values and never borrow an incidental worker Holder.
- Platform is a present execution scope, but ordinary tenant repositories,
  tenant-only HTTP clients and business-message construction still require a
  Tenant scope and fail before the relevant side effect.

## Supported evidence boundary

The public surfaces are exercised by independent Maven consumers:

- `examples/simple-notes-demo` uses the Kernel JDK executor and callback APIs,
  including direct-task and externally completed callback compositions that
  read business state through the real PostgreSQL Adapter.
- `verification/moduvera-spring-task-context-consumer` uses a real Spring
  `ApplicationContext`, selected and unselected executors, and actual `@Async`
  proxies.
- `verification/moduvera-reactor-context-consumer` uses native Reactor 3.8.7
  subscription context across a real scheduler.
- `verification/moduvera-spring-ai-context-consumer` uses Spring AI 2.0.1
  `ChatClient`, two `ToolCallingAdvisor` tool rounds and the final streaming
  response with a test-source scripted model. This proves framework mechanics,
  not an external model provider.
- the reference Apps use selected Servlet entry boundaries and tenant-only
  message reconstruction through production runtime Adapters. Plaintext Kafka
  Testcontainers verifies message contract and delivery behavior but does not
  verify producer authentication or destination ACLs; both remain deployment
  prerequisites.

This support does not install global Reactor Hooks or modify raw Threads, the
common pool or unselected executors. It does not automatically propagate
Spring Security context, MDC, tracing, transactions, database connections,
arbitrary SDK or Advisor callbacks, an inner Publisher, complete MVC async
return lifecycles, or cache/share business-data isolation. It does not support
Platform messages, nullable tenant message identity, a generic ignore-tenant
switch, ScopedValue, StructuredTaskScope or parallel-stream adaptation.
