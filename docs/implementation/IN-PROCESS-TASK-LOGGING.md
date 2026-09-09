# In-process task logging

The logging starter composes the existing immutable business Snapshot with the
complete OpenTelemetry Context and a temporary trusted MDC projection. Load the
[governed Agent runtime](../../verification/governed-observability/README.md)
for real tracing; the library does not create another SDK. Kernel remains free
of logging and OTel dependencies.

Use the boundary that owns the work. Ordinary local calls retain the current
context. A callback belonging to the same execution captures at registration
and opens no extra task Span:

```java
var callback = LoggingContextSnapshot.capture()
        .bindFunction(value -> applicationService.handle(value));
stage.thenApply(callback);
```

The snapshot also binds Runnable, Callable, Supplier, Consumer, BiFunction and
BiConsumer. `captureAllowingAbsent()` preserves missing business state;
`absent()` masks both business and telemetry state. Callback execution restores
only the owned MDC fields and the prior business/OTel state, including when
invoked inline or exceptionally. Bind at registration even if another thread
will complete the stage. A `thenCompose` binding covers the synchronous
function, not a later asynchronous chain it returns.

A **new task** binds its actual body before passing it to the JDK executor:

```java
Future<Result> result = executor.submit(LoggingTasks.bindCallable(
        "inventory.reconcile", () -> applicationService.reconcile()));
executor.execute(LoggingTasks.bindRunnable(
        "inventory.refresh", () -> applicationService.refresh()));
```

Each invocation starts one INTERNAL child Span from the captured parent, even
when that parent has ended, and logs one `task.execute` INFO at actual exit.
`task_name` is a stable bounded identifier, not user input or a business payload.
`duration_ms` uses a monotonic clock beginning at execution, excluding queue
time. The canonical reports success when the body returns and failure when it
throws; the original return value or exception reaches the caller unchanged.
A cancellation notification is not proof that an interruption-ignoring body
has stopped: its scope and completion event remain attached to actual exit.
The cancellation boundary owns that separate decision; a body that consumes
interruption and successfully returns is observed as a successful body.

The JDK executor owns its original Future, rejection policy, queue and shutdown
contract. Binding creates no Span or log. Rejection and cancellation before a
submitted Future starts produce no task completion event. Wrap the **body before
submission**, never an existing FutureTask or opaque framework dispatch wrapper:
those wrappers can return without invoking their business body and can hide its
exception. Do not install a new-task wrapper as a generic Spring TaskDecorator.
The existing Kernel/Spring business-only propagators retain their existing
contract; this logging opt-in is explicit at the actual task/callback boundary.

A task wrapper does not decide recovery, retry, or final failure ownership.
A Future consumer, enclosing execution boundary, or configured uncaught-error
handler records its actual recovery WARN or final ERROR with a stable code and
safe cause according to the [logging contract](../logging/LOGGING.md). The
wrapper records canonical INFO only and rethrows, so it never logs a duplicate
final ERROR before another component accepts the exception.

Bound tasks and callbacks belong to their captured logical execution. Never
cache them across requests, including two requests in the same tenant. Bind a
fresh task for each new submitting execution. Child identity changes do not
write back into the parent snapshot. Business identity and permissions come
only from trusted ExecutionContext; telemetry Baggage never establishes them.
No connection, transaction or arbitrary ThreadLocal is copied. Relay polling and lease takeover
remain outside this new-business-task boundary; no scheduler or persistent-job
platform is introduced.

## Verification

`LoggingTasksTest` uses real platform and virtual executors, CallerRuns and
CompletableFuture callbacks with latches. The independent Boot fixture checks
actual thread state and captures real Agent OTLP exports plus ECS stdout:

```bash
./mvnw -pl framework/starters/moduvera-logging-spring-boot-starter,verification/governed-observability/logging-fixture,verification/governed-observability/agent-extension -am verify
MODUVERA_OTEL_JAVAAGENT=/absolute/path/to/locked-agent.jar \
MODUVERA_TASK_EVIDENCE_DIR=/absolute/path/to/external-evidence \
  bash verification/governed-observability/verify-task-fixture.sh
```

The runtime analyzer verifies parentage, sampled and unsampled behavior,
canonical counts, no callback or never-started task Spans, cancellation timing,
and restoration observations. These are focused task checks; they do not
replace the repository Normal Gate or qualify unrelated transport lifecycles.
