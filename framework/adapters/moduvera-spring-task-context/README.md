# Spring task execution context adapter

`moduvera-spring-task-context` is an optional Spring Core adapter for task
execution. It keeps Spring types out of `moduvera-kernel` and does not bring
Reactor or Spring AI into applications that only use Spring task execution.

Create one reusable `ExecutionContextTaskDecorator` and install it only on the
`TaskExecutor` instances that should carry Execution Context:

```java
@Bean("businessTaskExecutor")
ThreadPoolTaskExecutor businessTaskExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(new ExecutionContextTaskDecorator());
    return executor;
}

@Async("businessTaskExecutor")
public CompletableFuture<Result> runBusinessTask() {
    return CompletableFuture.completedFuture(processWith(ExecutionContextHolder.require()));
}
```

Spring calls the decorator for every actual task submission. At that point the
adapter uses `ExecutionContextSnapshot.captureAllowingAbsent()` and returns its
named `BoundRunnable`. The bound task installs the captured Tenant, Platform,
or explicit absent state only while the delegate actually runs. Parent Scope
exit, cancellation, or timeout notification does not invalidate the snapshot
or close a worker Scope; actual delegate exit restores the worker's previous
state.

Only tasks submitted through a configured executor are covered. An `@Async`
method is covered only when its proxy routes through that executor. Raw
threads, the common pool, other executors, scheduler products, Spring Security
context, transactions, MDC, and tracing are unchanged. Request-owned Future or
SDK callbacks still need registration-time binding with
`ExecutionContextSnapshot`; selecting an executor does not bind later callback
registration automatically.

The decorator preserves the executor's native value, rejection, cancellation,
and lifecycle behavior. Spring may decorate an internal Future task rather
than the user Runnable, so exceptions from `submit` and `@Async` Future methods
must be observed through their returned Future. A TaskDecorator cannot make
every such exception visible to an uncaught-exception handler.
