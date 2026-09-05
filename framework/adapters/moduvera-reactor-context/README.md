# Reactor execution context adapter

`moduvera-reactor-context` is an optional Reactor 3.8 adapter. It keeps Reactor
types out of `moduvera-kernel` and out of consumers that only need the JDK
execution-context API.

Use one write boundary and then opt synchronous business callbacks into Holder
restoration:

```java
Flux<Result> results = ReactorExecutionContexts.withContext(
        trustedContext,
        ReactorExecutionContexts.mapInContext(source, this::process));
```

`withContext` accepts a context already trusted by the caller. `propagate`
strictly captures the current Holder when `propagate` is called, even if the
Publisher is subscribed later on another thread after the parent Scope exits.
`require(ContextView)` reads only the native Reactor Context and fails for a
missing or wrongly typed reserved value; it never borrows the signal thread's
Holder.

`mapInContext` is a reusable identity-free template. For every subscription it
reads that subscriber's native context and restores the corresponding present
or explicitly absent snapshot only while the synchronous mapper runs. Normal
return, failure, retry, and actual callback exit restore the signal thread's
previous Holder. Cancellation notification does not close a Scope that is
still active in a synchronous mapper.

A Publisher returned by `withContext` or `propagate` contains a fixed logical
execution identity. Do not cache it or share it across requests, including
same-tenant requests with different Actor, Initiator, or correlation. The
adapter does not register a global Hook and does not make arbitrary `map` or
`flatMap` callbacks, an inner asynchronous Publisher, or third-party code
Holder-aware. It also does not select schedulers for blocking I/O. Reactor
`cache` and `share` retain their normal data-sharing semantics; native context
propagation is not evidence of tenant isolation for shared business data.
