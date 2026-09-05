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

Runnable and Callable wrappers use this same Scope lifecycle on the thread
that actually invokes the delegate. They preserve return values and the
delegate's RuntimeException, Error, or checked Exception. A captured Snapshot
does not depend on the lifetime of its parent Scope and may be reused only for
callbacks belonging to that same logical execution; it must not be cached by
tenant or shared across requests.

This decision establishes only the existing Tenant-model recovery kernel.
Platform scope, tenant-resource guards, named callback binding types, JDK and
Spring executors, HTTP, Reactor, Spring AI, message and job adapters, and
cross-boundary composition remain planned work in the approved ExecutionContext
ticket graph. This ADR does not claim transparent propagation, zero allocation,
transaction or connection propagation, or support for those future adapters.

Evidence: Kernel tests for null rejection, unique binding order, cross-thread
and out-of-order failure atomicity, present/absent restoration, exception
transparency, legacy wrappers, and post-parent-lifetime Snapshot execution.
