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

Named callback binding types, JDK and Spring executors, HTTP entry mapping,
Reactor, Spring AI, message adaptation and cross-boundary composition remain
planned work in the approved ExecutionContext ticket graph. This ADR does not
claim transparent propagation, zero allocation, transaction or connection
propagation, or support for those future adapters.

Evidence: Kernel tests for the three states, strict tenant reads, full-value
restoration, null rejection, unique binding order, cross-thread and out-of-order
failure atomicity, exception transparency, legacy calls, and post-parent-lifetime
Snapshot execution; production Adapter tests against PostgreSQL and MySQL;
focused tenant-only HTTP and Job/Lock tests; and an independent Maven consumer.
