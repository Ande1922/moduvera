---
status: accepted
---

# Use explicit restorable Execution Context snapshots

Execution Context propagation starts from one JDK-only restoration kernel in
`moduvera-kernel`. Opening a Scope installs a distinct binding owned by the
current thread. The binding, rather than the context object's identity, defines
the close order. A close on another thread or outside reverse-open order fails
without changing the holder, while repeated close after a valid close is
idempotent. Every successful close restores the exact preceding binding,
including an explicitly absent state.

A Snapshot is a fixed present-or-absent state. Strict capture fails immediately
when no Execution Context exists; allowing-absent capture preserves the
caller's present or absent state. A separate explicit absent factory lets a
trusted native carrier express a missing value without consulting the worker
thread. Opening that snapshot hides any worker identity for the synchronous
delegate and restores it when the delegate returns or throws. Explicit trusted
Snapshot construction rejects null.

This includes two compatibility-relevant behavior corrections. Previously,
`ExecutionContextHolder.open(null)` installed an implicit absent state; it now
rejects null before changing the Holder. A caller that receives an explicitly
absent native-carrier value must use `ExecutionContextSnapshot.absent()` and
`openScope()`, while a caller capturing the current present-or-absent state uses
`captureAllowingAbsent()`. Previously, `ExecutionContextSnapshot.wrap(Callable)`
converted a checked Exception into a private RuntimeException wrapper; it now
propagates the original checked Exception unchanged.

Runnable and Callable wrappers use this same Scope lifecycle on the thread
that actually invokes the delegate. They preserve return values and the
delegate's RuntimeException, Error, or checked Exception. A captured Snapshot
does not depend on the lifetime of its parent Scope and may be reused only for
callbacks belonging to that same logical execution; it must not be cached by
tenant or shared across requests.

Execution Context is one immutable value containing an explicit Execution
Scope, Actor, Initiator and correlation identifier. The scope is either
Platform or Tenant with a valid non-null Tenant ID; absence remains the third
Holder state. `require()` accepts either present scope, while
`requireTenantId()` and the legacy `tenantId()` alias reject Platform. Scope
nesting and snapshots restore the full value, including transitions between
Platform, different tenants, same-tenant executions with different identity or
correlation, and absence. Installing a trusted context does not authorize its
scope.

Ordinary tenant persistence and tenant-only direct HTTP clients require a
Tenant scope before issuing SQL or a remote request. Platform and absence
reject reads and writes without persisting the rejected change, while normal
tenant isolation remains.
Job execution retains its caller-supplied context independently of lock scope:
a GLOBAL lock is global competition only, and a TENANT lock still requires a
Tenant execution.

Changing the first `ExecutionContext` record component from `tenantId` to
`scope` is a structural compatibility change. The former
`ExecutionContext(TenantId, Actor, Initiator, String)` constructor,
`initiatedBy(TenantId, ...)`, `tenantId()`, Holder run/call and Snapshot
Runnable/Callable wrappers remain callable and tenant-strict. Their method
descriptors preserve ordinary precompiled tenant callers, but record-component
reflection, record-pattern source, generated `toString` and serializers that
derive shape from record components now observe `scope`. The type does not
implement Java serialization and is not a network DTO; message wire contracts
remain separate and tenant-only.

The sections below define scenario-specific contracts. A planned design
boundary is not runtime support evidence. This ADR does not claim transparent
propagation, zero allocation, or transaction or connection propagation.

Read the [historical qualification record](../../.scratch/execution-context-propagation/evidence/adr-0037-qualification.md)
only when tracing recorded tests, versions, or consumer qualification; the
decisions and limits needed for changes are contained in this ADR.

## Request-bound callbacks

`ExecutionContextSnapshot` constructs seven immutable named binding types for `Runnable`,
`Callable`, `Supplier`, `Function`, `Consumer`, `BiFunction` and `BiConsumer`. Each binding
opens the fixed present-or-absent snapshot only around an actual synchronous delegate call,
then restores the invoking thread on normal return or failure. The legacy Runnable and
Callable `wrap` methods delegate to those same named bindings.

Request-owned Future or SDK callbacks strictly capture at registration. A completed Future
may invoke inline, an incomplete Future may invoke on its completing thread, and an async
callback may use a specified Executor; none of those execution choices changes the bound
identity. The binding remains usable after its parent Scope exits and may be retried or
invoked repeatedly within the same logical execution. An absent snapshot hides a worker's
existing identity for the call.

Ordinary business functions may be shared, while every fixed snapshot and named binding
belongs to one logical execution and must not be cached or shared across requests, including
requests for the same tenant. A `thenCompose` binding covers only its synchronous Function
call; asynchronous work represented by the returned stage needs propagation at its own
boundary. A long-lived listener therefore stays unbound and creates a binding from each
trusted event's context instead of retaining the listener-registration identity.

## JDK executors

`ContextExecutors` creates reusable decorators without capturing their construction thread.
Every direct `execute`, `submit`, `invokeAll` or `invokeAny` task captures the submitting
thread's present or absent state, then opens that fixed snapshot only while the task actually
runs. Platform threads, virtual threads, inline execution and `CallerRunsPolicy` use the same
restoration path. The decorator does not intercept raw Threads, the common pool or any executor
that the consumer did not explicitly decorate.

The ExecutorService decorator calls the corresponding delegate submission, bulk and lifecycle
methods. It returns the delegate's Futures and the exact `shutdownNow` queued-task list; it
forwards shutdown, termination waiting and close directly. Cancellation and timeout therefore
remain notifications owned by the delegate: they do not clear another thread's context, and an
interrupted task keeps its snapshot until its delegate actually exits. An already-bound callback
may be submitted through the decorator safely; its inner fixed snapshot wins for the callback
call, with ordinary nested restoration and no reflective task unwrapping.

## Spring task executors

`ExecutionContextTaskDecorator` is a reusable, identity-free Spring `TaskDecorator`. Spring
invokes it for each actual task submission, where it uses `captureAllowingAbsent()` and a named
`BoundRunnable`; constructing the decorator or its containing Bean does not capture a request.
The bound task installs the submitting thread's Tenant, Platform or explicit absent state only
while the delegate actually runs, then restores the worker's full prior identity at actual exit.

Only TaskExecutor instances configured with the decorator are covered. An `@Async` invocation is
covered only when the real proxy routes it through one of those selected executors. Other
executors, raw Threads and the common pool remain unchanged. Future or SDK callbacks owned by a
request still use registration-time Snapshot binding; task submission propagation does not bind
callbacks registered later.

The Spring executor retains ownership of values, Future exception observation, rejection,
cancellation and lifecycle. Cancellation or timeout notification does not close another
thread's Scope: a running task keeps its snapshot until its delegate exits. Spring may decorate
an internal Future task, so failures from `submit` and Future-returning `@Async` methods are
observed through the returned Future rather than assumed visible from `Runnable.run`.

## HTTP execution boundaries

Each Servlet App explicitly supplies an `ExecutionContextHandlerSelection` for
its managed Controller types or packages and may name excluded non-business
handlers. Selection is independent of `permitAll`, JWT presence, tenant claims
and `Tenant-Id`; exclusion only disables this context adapter and does not
change Spring Security authentication or authorization.

After the security filter chain authenticates and authorizes the request,
Spring MVC resolves the actual `HandlerMethod`. The context interceptor then
resolves `@ExecutionBoundary` at method, class and default-Tenant precedence
and opens the full Actor, Initiator, Scope and correlation context before the
Controller invokes its use case. A valid USER JWT may omit `tenant_id`:
Platform handlers accept it, while Tenant handlers reject it with 403 before
the Controller. Tenant handlers retain SERVICE target-header requirements and
asserted/requested tenant conflict rejection. Platform handlers ignore a
client `Tenant-Id` for scope selection.

One generated correlation is shared across the Controller, security Problem
and selected redispatches.

One Scope belongs to one actual dispatch thread. Synchronous completion and
error unwinding close it in `afterCompletion`; an asynchronous handoff closes
it in `afterConcurrentHandlingStarted`; an ASYNC redispatch opens a new Scope
after resolving its handler. Error handlers receive a context only if the App
selects them. The first phase does not transparently propagate context into an
MVC `Callable`, `DeferredResult` producer or arbitrary asynchronous callback.

For HTTP integration setup and lifecycle examples, see
[`HTTP-EXECUTION-BOUNDARIES.md`](../implementation/HTTP-EXECUTION-BOUNDARIES.md).

## Reactor context templates

The optional `moduvera-reactor-context` Adapter keeps Reactor types out of the
Kernel. `withContext` writes a trusted value and `propagate` strictly captures
the Holder when the template is called; both return a Publisher fixed to that
logical execution. `require(ContextView)` reads only the native carrier and
rejects a missing key or wrongly typed value without consulting the signal
thread's Holder.

`mapInContext` is an identity-free template. Each subscription reads its native
Context and creates a present or explicitly absent Snapshot. Each actual
synchronous mapper invocation opens and closes that Snapshot around the
delegate, restoring the full preceding worker identity after normal return,
failure, retry, or the actual exit of a cancelled callback. Missing native
state hides a foreign worker identity; Platform and Tenant scope retain their
normal strict access rules.

The adapter installs no global Hook and does not make arbitrary map/flatMap
callbacks, inner asynchronous Publishers, blocking I/O, or third-party code
Holder-aware. Fixed-context Publishers must not be cached or shared across
requests, including same-tenant requests with different identity or
correlation. Reactor cache/share data semantics require their own tenant
isolation proof.

## AI request and streaming response context

The optional `moduvera-spring-ai-context` Adapter captures one trusted context
per request. A request-scoped Advisor validates the fully assembled Advisor
request context and concrete ToolCallingChatOptions, then supplies the same
value under one public reserved key in both native carriers. Existing defaults,
direct entries and provider options are retained; a different effective
reserved value is rejected before model or tool delegation. The captured
request object is fixed to one logical request and is never a shared ChatClient
default.

The response mapper is stateless and reads every current ChatClientResponse. It
rejects a missing or wrongly typed reserved value before its delegate, opens a
Scope only for the synchronous response function, and restores the prior Holder
after success or failure. Response consumers must retain ChatClientResponse;
content strings do not carry its native context.

## AI tool loop context

`SpringAiExecutionContexts.toolCallback(...)` is a stateless wrapper. Every
invocation reads the reserved `ExecutionContext` from that call's native
ToolContext, rejects a missing ToolContext, missing key, wrong value type, or
the context-free callback entry before invoking the delegate, and never falls
back to a Holder value already present on the worker. It opens a Scope only for
the synchronous delegate call and preserves the delegate's definition,
metadata, input, result, and exception contract. The wrapper captures no
request snapshot, so it can be shared when the delegate itself is safe to
share.

Custom Advisor code that creates internal asynchronous callbacks must still
propagate context explicitly at those callback boundaries. Opening a Scope
around `return nextStream(...)` covers only synchronous Publisher assembly and
cannot cover the stream's later signals.

## Tenant-only message compatibility

Outbound business-message adapters require a concrete Tenant before constructing or
appending an envelope. Platform and missing context therefore cannot create a tenantless
message or Outbox intent. A valid persisted envelope owns the tenant and execution identity
needed for later delivery, so a background relay does not depend on the originating request
Holder.

Every inbound delivery validates the tenant-only envelope and provider contract before
Application invocation, then reconstructs Tenant, Initiator and correlation from that message
with the consumer's local Actor and permissions. This source and contract matching is not
producer authentication. Trust in the envelope depends on deployment-level authenticated
producers and destination ACLs; wire permissions remain untrusted. The per-message scope
restores the listener thread's exact prior identity after success, retry, duplicate or rejection.

For message integration setup, see
[the tenant-only message context guide](../implementation/TENANT-ONLY-MESSAGE-CONTEXT.md).

## Composition and support boundaries

The Kernel artifact remains JDK-only. Spring task consumers do not acquire
Reactor or Spring AI, and Reactor consumers do not acquire Spring AI. Consumers
that use none of these optional adapters acquire none of their frameworks.

An explicit trusted-Holder consumer seam does not establish an HTTP-to-async
chain. Servlet entry behavior and negative write/message side effects retain
their separate runtime evidence. Qualification with a test-source scripted AI
model makes no external provider claim.

The supported adoption and lifecycle boundary is recorded in
[`EXECUTION-CONTEXT-PROPAGATION.md`](../implementation/EXECUTION-CONTEXT-PROPAGATION.md). Support
is limited to the explicit public seams and consumers named there. In particular, plaintext Kafka
runtime tests do not verify producer authentication or destination ACLs; those remain deployment
prerequisites for trusting message producers.
