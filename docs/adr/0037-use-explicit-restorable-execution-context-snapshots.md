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
Tenant scope before issuing SQL or a remote request. PostgreSQL and MySQL
runtime Adapter tests prove Platform and absence reject reads and writes
without persisting the rejected change, while normal tenant isolation remains.
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
implement Java serialization, is not a network DTO, and repository consumers
do not serialize or reflectively destructure it; message wire contracts remain
separate and tenant-only.

Scenario-specific contracts and implementation evidence are recorded in the
owned sections below. A planned section is an approved design boundary, not
runtime support evidence. This ADR does not claim transparent propagation,
zero allocation, or transaction or connection propagation.

Evidence: Kernel tests for the three states, strict tenant reads, full-value
restoration, null rejection, unique binding order, cross-thread and out-of-order
failure atomicity, exception transparency, legacy calls, and post-parent-lifetime
Snapshot execution; production Adapter tests against PostgreSQL and MySQL;
focused tenant-only HTTP and Job/Lock tests; and an independent Maven consumer.

## Request-bound callbacks — ticket 03

Status: verified for the Kernel callback binding surface and independent consumer usage.

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

Evidence: Kernel tests exercise all seven public JDK shapes, original outcomes, present and
absent restoration, completed/externally completed/async CompletableFuture callbacks,
parent-Scope exit, retries, same-tenant distinct identities and the `thenCompose` boundary.
The independent `simple-notes-demo` consumer compiles and runs per-request function binding
through an SDK-shaped callback registration/later-trigger driver, plus a separate
per-trusted-event long-lived listener example, using only the public Kernel API.

## JDK executors — ticket 04

Status: verified for explicitly decorated JDK Executor and ExecutorService instances.

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

Evidence: Kernel tests compare original and decorated real JDK executors for values, failures,
Future cancellation, rejection, bulk calls, timeout and lifecycle behavior. They also exercise a
reused single platform worker with distinct tenants, same-tenant distinct identity, Platform and
absence; a virtual-thread-per-task executor; inline and CallerRuns execution; cancellation before
start and during delayed interruption; delegate-owned queued Future tasks; explicit Bound
nesting; and restoration after actual exit. The independent `simple-notes-demo` consumer shows
that executor submission capture covers the submitted task, while a Future callback still needs
its own registration-time binding.

## Spring task executors — ticket 05

Status: planned.

Selected TaskExecutor instances use a reusable TaskDecorator. Actual Async proxy routing defines the propagation boundary.

Implementation and consumer evidence remain the responsibility of ticket 05.

## HTTP execution boundaries — ticket 06

Status: implemented and verified.

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

One Scope belongs to one actual dispatch thread. Synchronous completion and
error unwinding close it in `afterCompletion`; an asynchronous handoff closes
it in `afterConcurrentHandlingStarted`; an ASYNC redispatch opens a new Scope
after resolving its handler. Error handlers receive a context only if the App
selects them. The first phase does not transparently propagate context into an
MVC `Callable`, `DeferredResult` producer or arbitrary asynchronous callback.

Evidence: focused resolver/interceptor tests; embedded Tomcat with RSA-signed
JWTs, virtual request threads, real Security and MVC chains, method/class/default
selection, 401/403 Problem responses, side-effect guards, excluded handlers,
same-tenant distinct identities and REQUEST/ASYNC/ERROR cleanup; Catalog App
consumer IT with full virtual-thread identity and same-thread cleanup; one
generated correlation across Controller, security Problem and selected
redispatches; and the applicable reference-product HTTP scenarios. Usage and
the supported lifecycle are documented in
[`HTTP-EXECUTION-BOUNDARIES.md`](../implementation/HTTP-EXECUTION-BOUNDARIES.md).

## Reactor context templates — ticket 07

Status: planned.

Subscription Context is the native carrier. Selected synchronous callbacks restore the Holder without global hooks or thread-identity fallback.

Implementation and consumer evidence remain the responsibility of ticket 07.

## AI request and streaming response context — ticket 08

Status: implemented.

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

Evidence: Spring AI 2.0.1 with Spring Boot 4.1.1 and Java 26; Adapter unit tests;
and an independent consumer using a delayed, thread-shifting real
`ChatClient.stream().chatClientResponse()` subscription with concurrent Tenant,
same-Tenant/different-identity, and Platform requests. Tool callback wrapping
and multi-round tool-loop evidence remain owned by ticket 09.

## AI tool loop context — ticket 09

Status: planned.

Each synchronous tool invocation reads its own ToolContext. The shared adapter retains no request snapshot and preserves real multi-round loop behavior.

Implementation and consumer evidence remain the responsibility of ticket 09.

## Tenant-only message compatibility — ticket 10

Status: planned.

Business message construction requires Tenant. Trusted persisted messages remain deliverable by a background relay without the original request Holder.

Implementation and consumer evidence remain the responsibility of ticket 10.

## Composition and consumer qualification — ticket 11

Status: planned.

Cross-boundary consumer evidence and dependency closure determine support claims. Planned or unverified behavior is never promoted by this summary.

Implementation and consumer evidence remain the responsibility of ticket 11.
