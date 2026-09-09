package io.github.ande1922.moduvera.verification.logging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
import io.github.ande1922.moduvera.logging.LoggingTasks;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Real JDK task bodies and callbacks; assertions execute on the threads being qualified. */
final class TaskFixture {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskFixture.class);
    private static final ContextKey<String> EXTRA = ContextKey.named("fixture.task.context");
    private static final ExecutionContext WORKER = identity("worker", "worker");

    private TaskFixture() {}

    static void run() throws Exception {
        check(Span.current().getSpanContext().isValid() == false, "fixture starts without a span");
        queuedTasksAndCallbacks();
        virtualAndCallerRuns();
        cancellation();
        unsampled();
        failures();
        clean("fixture-end");
        LOGGER.atInfo().addKeyValue("fixture_phase", "complete").log("任务运行验证完成");
    }

    private static void queuedTasksAndCallbacks() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> blocker = pool.submit(() -> { occupied.countDown(); await(release); });
            try {
                await(occupied);
                List<Future<String>> results = new ArrayList<>();
                for (int i = 1; i <= 3; i++) {
                    String name = "queued-" + i;
                    ExecutionContext request = identity(i == 2 ? "b" : "a", "actor-" + i);
                    Span parent = parent(name);
                    Callable<String> bound;
                    try (var business = ExecutionContextHolder.open(request);
                            var telemetry = Context.root().with(parent).with(EXTRA, name).makeCurrent();
                            var logging = LoggingContextSnapshot.capture().openScope()) {
                        SpanContext parentContext = parent.getSpanContext();
                        bound = LoggingTasks.bindCallable(name, () -> {
                            started.incrementAndGet();
                            observed(name, "inside", request, name);
                            check(!Span.current().getSpanContext().getSpanId().equals(parentContext.getSpanId()), "new child span");
                            if (name.equals("queued-1")) {
                                SpanContext outer = Span.current().getSpanContext();
                                LoggingTasks.bindRunnable("nested", () -> observed("nested", "inside", request, name)).run();
                                check(Span.current().getSpanContext().equals(outer), "nested span restored");
                                observed(name, "nested-restored", request, name);
                            }
                            return name;
                        });
                        observed(name, "submitted", request, name);
                    } finally {
                        parent.end();
                    }
                    results.add(pool.submit(() -> inWorker(name, bound)));
                }
                Callable<String> absent = LoggingTasks.bindCallable("queued-absent", () -> {
                    started.incrementAndGet();
                    observed("queued-absent", "inside", null, null);
                    return "absent";
                });
                Future<String> absentResult = pool.submit(() -> inWorker("queued-absent", absent));
                Future<?> cancelled = pool.submit(LoggingTasks.bindRunnable("never-started", () -> { throw new AssertionError("cancelled task ran"); }));
                check(cancelled.cancel(false), "queued cancellation accepted");
                LOGGER.atInfo().addKeyValue("task_name", "never-started")
                        .addKeyValue("fixture_phase", "owner-disposition")
                        .addKeyValue("task_disposition", "cancelled_before_start")
                        .log("取消方已取消尚未开始的任务");
                check(started.get() == 0, "no task body before queue release");
                LOGGER.atInfo().addKeyValue("fixture_phase", "queue-release").log("解除任务排队等待");
                release.countDown();
                blocker.get(10, TimeUnit.SECONDS);
                for (int i = 0; i < results.size(); i++) {
                    check(results.get(i).get(10, TimeUnit.SECONDS).equals("queued-" + (i + 1)), "return value preserved");
                }
                check(absentResult.get(10, TimeUnit.SECONDS).equals("absent"), "absent return preserved");
                check(started.get() == 4, "only actual task bodies ran");
                callbacks(pool);
                pool.submit(() -> clean("pool-after")).get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private static void callbacks(ExecutorService pool) throws Exception {
        Span parent = parent("callback");
        ExecutionContext request = identity("callback", "registered");
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> target;
        try (var business = ExecutionContextHolder.open(request);
                var telemetry = Context.root().with(parent).with(EXTRA, "callback").makeCurrent()) {
            LoggingContextSnapshot snapshot = LoggingContextSnapshot.capture();
            SpanContext parentContext = parent.getSpanContext();
            target = source.thenApply(snapshot.bindFunction(value -> {
                observed("callback", "external", request, "callback");
                check(Span.current().getSpanContext().equals(parentContext), "callback adds no span");
                return value + "-done";
            }));
            CompletableFuture.completedFuture("inline").thenAccept(snapshot.bindConsumer(value -> {
                observed("callback", value, request, "callback");
                check(Span.current().getSpanContext().equals(parentContext), "inline callback adds no span");
            })).join();
        } finally {
            parent.end();
        }
        pool.submit(() -> inWorker("callback", () -> { source.complete("value"); return null; })).get(10, TimeUnit.SECONDS);
        check(target.get(10, TimeUnit.SECONDS).equals("value-done"), "callback result preserved");
    }

    private static void virtualAndCallerRuns() throws Exception {
        ExecutionContext request = identity("inline", "caller");
        Span parent = parent("thread-modes");
        try (var business = ExecutionContextHolder.open(request);
                var telemetry = Context.root().with(parent).with(EXTRA, "modes").makeCurrent();
                var logging = LoggingContextSnapshot.capture().openScope();
                ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, String> before = MDC.getCopyOfContextMap();
            Callable<String> bound = LoggingTasks.bindCallable("virtual", () -> {
                check(Thread.currentThread().isVirtual(), "virtual thread executes body");
                observed("virtual", "inside", request, "modes");
                return "virtual-result";
            });
            check(virtual.submit(() -> inWorker("virtual", bound)).get(10, TimeUnit.SECONDS).equals("virtual-result"), "virtual result preserved");
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (ThreadPoolExecutor caller = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy())) {
                caller.execute(() -> { occupied.countDown(); await(release); });
                try {
                    await(occupied);
                    Thread submitting = Thread.currentThread();
                    caller.execute(LoggingTasks.bindRunnable("caller-runs", () -> {
                        check(Thread.currentThread() == submitting, "CallerRuns executes inline");
                        observed("caller-runs", "inside", request, "modes");
                    }));
                    check(MDC.getCopyOfContextMap().equals(before), "CallerRuns restores MDC exactly");
                    check(Span.current().getSpanContext().equals(parent.getSpanContext()), "CallerRuns restores parent span");
                    observed("caller-runs", "restored", request, "modes");
                } finally {
                    release.countDown();
                }
            }
        } finally {
            parent.end();
        }
    }

    private static void cancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutionContext request = identity("cancel", "running");
        Span parent = parent("cancel");
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> running;
            try (var business = ExecutionContextHolder.open(request);
                    var telemetry = Context.root().with(parent).with(EXTRA, "cancel").makeCurrent()) {
                running = pool.submit(LoggingTasks.bindCallable("running-cancel", () -> {
                    observed("running-cancel", "inside", request, "cancel");
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException expected) {
                        observed("running-cancel", "cancel-observed", request, "cancel");
                        interrupted.countDown();
                        await(release);
                        observed("running-cancel", "before-exit", request, "cancel");
                        throw expected;
                    }
                    return null;
                }));
            } finally {
                parent.end();
            }
            try {
                await(entered);
                check(running.cancel(true), "running cancellation accepted");
                await(interrupted);
                check(running.isDone() && running.isCancelled(), "Future already notified");
                LOGGER.atInfo().addKeyValue("fixture_phase", "cancellation-notified").log("Future 已通知取消，任务尚未退出");
            } finally {
                release.countDown();
            }
            pool.submit(() -> clean("cancel-after")).get(10, TimeUnit.SECONDS);
        }
    }

    private static void unsampled() throws Exception {
        SpanContext remote = SpanContext.createFromRemoteParent(
                "33333333333333333333333333333333", "4444444444444444",
                TraceFlags.getDefault(), TraceState.builder().put("vendor", "retained").build());
        ExecutionContext request = identity("unsampled", "unsampled");
        Callable<String> bound;
        try (var business = ExecutionContextHolder.open(request);
                var telemetry = Context.root().with(Span.wrap(remote)).with(EXTRA, "unsampled").makeCurrent()) {
            bound = LoggingTasks.bindCallable("unsampled", () -> {
                observed("unsampled", "inside", request, "unsampled");
                SpanContext child = Span.current().getSpanContext();
                check(child.getTraceId().equals(remote.getTraceId()), "unsampled trace retained");
                check(!child.getSpanId().equals(remote.getSpanId()), "unsampled new task span");
                check(!child.isSampled() && child.getTraceState().equals(remote.getTraceState()), "sampling and trace state retained");
                return "unsampled";
            });
        }
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            check(pool.submit(bound).get(10, TimeUnit.SECONDS).equals("unsampled"), "unsampled body ran");
            pool.submit(() -> clean("unsampled-after")).get(10, TimeUnit.SECONDS);
        }
    }

    private static void failures() throws Exception {
        IllegalStateException failure = new IllegalStateException("fixture controlled failure");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (pool) {
            Future<?> result = pool.submit(LoggingTasks.bindCallable("failed", () -> { throw failure; }));
            try {
                result.get(10, TimeUnit.SECONDS);
                throw new AssertionError("failure not propagated");
            } catch (ExecutionException observed) {
                check(observed.getCause() == failure, "original exception preserved");
                LOGGER.atWarn().log("调用方已执行验证兜底");
            }
            pool.submit(() -> clean("failure-after")).get(10, TimeUnit.SECONDS);
        }
        check(pool.isShutdown() && pool.isTerminated(), "close preserves shutdown contract");
        try {
            pool.submit(LoggingTasks.bindRunnable("rejected", () -> { throw new AssertionError("rejected task ran"); }));
            throw new AssertionError("submission not rejected");
        } catch (RejectedExecutionException expected) {
            LOGGER.atInfo().addKeyValue("task_name", "rejected")
                    .addKeyValue("fixture_phase", "owner-disposition")
                    .addKeyValue("task_disposition", "rejected_after_shutdown")
                    .log("提交方确认执行器关闭后拒绝任务，未重新提交");
            clean("rejection-after");
        }
        AssertionError finalFailure = new AssertionError("fixture controlled terminal failure");
        try {
            LoggingTasks.bindRunnable("fatal", () -> { throw finalFailure; }).run();
            throw new AssertionError("error not propagated");
        } catch (AssertionError observed) {
            check(observed == finalFailure, "original Error preserved");
            LOGGER.atError().addKeyValue("error.code", "SYS_UNEXPECTED")
                    .setCause(observed).log("调用方完成最终失败处置");
        }
        clean("fatal-after");
    }

    private static <T> T inWorker(String name, Callable<T> body) throws Exception {
        Span sentinel = parent("worker-" + name);
        try (var business = ExecutionContextHolder.open(WORKER);
                var telemetry = Context.root().with(sentinel).with(EXTRA, "worker").makeCurrent();
                var logging = LoggingContextSnapshot.capture().openScope();
                var unrelated = MDC.putCloseable("fixture_worker_key", "keep")) {
            Map<String, String> before = MDC.getCopyOfContextMap();
            try {
                return body.call();
            } finally {
                check(MDC.getCopyOfContextMap().equals(before), "exact worker MDC restored");
                check(Span.current().getSpanContext().equals(sentinel.getSpanContext()), "worker span restored");
                observed(name, "worker-restored", WORKER, "worker");
            }
        } finally {
            sentinel.end();
        }
    }

    private static void observed(String name, String phase, ExecutionContext expected, String extra) {
        check(ExecutionContextHolder.current().orElse(null) == expected, "complete business identity");
        check(java.util.Objects.equals(Context.current().get(EXTRA), extra), "complete custom Context");
        SpanContext span = Span.current().getSpanContext();
        check(span.isValid(), "Agent provides valid span");
        check(span.getSpanId().equals(MDC.get("span_id")), "MDC projects current span");
        if (expected == null) {
            check(MDC.get("actor_id") == null && MDC.get("tenant_id") == null && MDC.get("correlation_id") == null, "absent state masks worker identity");
        } else {
            check(expected.actor().subjectId().equals(MDC.get("actor_id")), "MDC actor");
            check(expected.initiator().subjectId().equals(MDC.get("initiator_id")), "MDC initiator");
            check(expected.correlationId().equals(MDC.get("correlation_id")), "MDC correlation");
            check(expected.requireTenantId().value().equals(MDC.get("tenant_id")), "MDC tenant");
        }
        LOGGER.atInfo().addKeyValue("fixture_case", name).addKeyValue("fixture_phase", phase)
                .addKeyValue("fixture_virtual", Thread.currentThread().isVirtual())
                .log("任务线程内部状态已验证");
    }

    private static void clean(String phase) {
        check(ExecutionContextHolder.current().isEmpty(), "business state absent after exit");
        check(Context.current().get(EXTRA) == null, "custom Context absent after exit");
        check(!Span.current().getSpanContext().isValid(), "span absent after exit");
        check(MDC.get("span_id") == null && MDC.get("actor_id") == null, "owned MDC absent after exit");
        LOGGER.atInfo().addKeyValue("fixture_phase", phase).log("执行线程恢复为空状态");
    }

    private static Span parent(String name) {
        return GlobalOpenTelemetry.getTracer("moduvera.task-fixture").spanBuilder("parent-" + name).setNoParent().startSpan();
    }

    private static ExecutionContext identity(String tenant, String actorId) {
        return new ExecutionContext(new TenantId(tenant), new Actor(ActorType.USER, actorId, Set.of("fixture:read")),
                new Initiator(ActorType.SERVICE, "fixture-origin"), "correlation-" + actorId);
    }

    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(10, TimeUnit.SECONDS), "controlled latch reached");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
