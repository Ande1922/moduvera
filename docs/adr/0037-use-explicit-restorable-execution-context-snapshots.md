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

Status: planned.

Registration captures the full logical execution. Ordinary functions may be shared; fixed request snapshots may not be reused across requests.

Implementation and consumer evidence remain the responsibility of ticket 03.

## JDK executors — ticket 04

Status: planned.

Direct task submission captures present or absent context. Future, cancellation and shutdown semantics remain owned by the delegated executor.

Implementation and consumer evidence remain the responsibility of ticket 04.

## Spring task executors — ticket 05

Status: planned.

Selected TaskExecutor instances use a reusable TaskDecorator. Actual Async proxy routing defines the propagation boundary.

Implementation and consumer evidence remain the responsibility of ticket 05.

## HTTP execution boundaries — ticket 06

Status: planned.

Managed handler selection is independent of authentication. Method, class and default Tenant declarations determine the execution range.

Implementation and consumer evidence remain the responsibility of ticket 06.

## Reactor context templates — ticket 07

Status: planned.

Subscription Context is the native carrier. Selected synchronous callbacks restore the Holder without global hooks or thread-identity fallback.

Implementation and consumer evidence remain the responsibility of ticket 07.

## AI request and streaming response context — ticket 08

Status: planned.

Each request injects one context into both native carriers. Response processing reads the current ChatClientResponse during real stream consumption.

Implementation and consumer evidence remain the responsibility of ticket 08.

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
