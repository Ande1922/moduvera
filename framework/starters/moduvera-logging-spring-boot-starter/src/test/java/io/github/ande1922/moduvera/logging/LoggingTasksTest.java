package io.github.ande1922.moduvera.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.io.IOException;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LoggingTasksTest {

    private static final ContextKey<String> EXTRA = ContextKey.named("task-test-extra");
    private static final Context TRACE = Context.root().with(Span.wrap(SpanContext.create(
            "11111111111111111111111111111111", "2222222222222222",
            TraceFlags.getDefault(), TraceState.getDefault()))).with(EXTRA, "captured");
    private final Logger logger = (Logger) LoggerFactory.getLogger("task.execute");
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void attach() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(events);
        events.stop();
        MDC.clear();
    }

    @Test
    void queuedInterleavedRequestsAndAbsentStateRestoreWorker() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> blocker = pool.submit(() -> {
                occupied.countDown();
                await(release);
            });
            await(occupied);
            List<ExecutionContext> identities = List.of(identity("a", "one"), identity("b", "two"), identity("a", "three"));
            java.util.ArrayList<Future<ExecutionContext>> results = new java.util.ArrayList<>();
            for (ExecutionContext identity : identities) {
                try (var ignored = ExecutionContextHolder.open(identity); var trace = TRACE.makeCurrent()) {
                    results.add(pool.submit(LoggingTasks.bindCallable("interleaved", () -> {
                        assertThat(Context.current().get(EXTRA)).isEqualTo("captured");
                        assertThat(MDC.get("correlation_id")).isEqualTo(identity.correlationId());
                        assertThat(MDC.get("actor_id")).isEqualTo(identity.actor().subjectId());
                        assertThat(MDC.get("initiator_id")).isEqualTo(identity.initiator().subjectId());
                        assertThat(MDC.get("tenant_id")).isEqualTo(identity.requireTenantId().value());
                        return ExecutionContextHolder.require();
                    })));
                }
            }
            Future<?> absent = pool.submit(LoggingTasks.bindRunnable("absent", () -> {
                assertThat(ExecutionContextHolder.current()).isEmpty();
                assertThat(MDC.get("actor_id")).isNull();
                assertThat(Context.current().get(EXTRA)).isNull();
            }));
            assertThat(events.list).isEmpty();
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get(5, TimeUnit.SECONDS)).isEqualTo(identities.get(i));
            }
            absent.get(5, TimeUnit.SECONDS);
            pool.submit(LoggingTasksTest::assertClean).get(5, TimeUnit.SECONDS);
            assertThat(events.list).hasSize(4);
        }
    }

    @Test
    void inlineNestedFailureAndAbsentMaskRestoreExactPriorState() throws Exception {
        ExecutionContext outer = identity("outer", "outer");
        Callable<Void> absent = LoggingTasks.bindCallable("absent-nested", () -> {
            assertThat(ExecutionContextHolder.current()).isEmpty();
            assertThat(Context.current().get(EXTRA)).isNull();
            assertThat(MDC.get("actor_id")).isNull();
            throw new IOException("exact checked failure");
        });
        try (var ignored = ExecutionContextHolder.open(outer); var trace = TRACE.makeCurrent()) {
            MDC.put("actor_id", "outer-mdc");
            MDC.put("unrelated", "keep");
            Map<String, String> before = MDC.getCopyOfContextMap();
            LoggingTasks.bindRunnable("outer", () -> {
                assertThat(ExecutionContextHolder.require()).isEqualTo(outer);
                assertThatThrownBy(absent::call).isInstanceOf(IOException.class);
                assertThat(ExecutionContextHolder.require()).isEqualTo(outer);
                assertThat(MDC.get("actor_id")).isEqualTo("outer");
                assertThat(Context.current().get(EXTRA)).isEqualTo("captured");
            }).run();
            assertThat(ExecutionContextHolder.require()).isEqualTo(outer);
            assertThat(Context.current()).isSameAs(TRACE);
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
        }
        assertThat(events.list).hasSize(2);
    }

    @Test
    void cancellationBeforeStartAndRejectionDoNotExecuteOrLog() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (pool) {
            pool.submit(() -> { occupied.countDown(); await(release); });
            await(occupied);
            Future<?> cancelled = pool.submit(LoggingTasks.bindRunnable("not-started", () -> { throw new AssertionError(); }));
            assertThat(cancelled.cancel(false)).isTrue();
            release.countDown();
            pool.submit(LoggingTasksTest::assertClean).get(5, TimeUnit.SECONDS);
            assertThat(cancelled.isCancelled()).isTrue();
            pool.shutdown();
            assertThatThrownBy(() -> pool.submit(LoggingTasks.bindRunnable("rejected", () -> {})))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(events.list).isEmpty();
        } finally {
            release.countDown();
        }
        assertThat(pool.isShutdown()).isTrue();
        assertThat(pool.isTerminated()).isTrue();
    }

    @Test
    void cancellationNotificationDoesNotCloseRunningScope() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutionContext request = identity("cancel", "running");
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> running;
            try (var ignored = ExecutionContextHolder.open(request); var trace = TRACE.makeCurrent()) {
                running = pool.submit(LoggingTasks.bindCallable("running-cancel", () -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException expected) {
                        assertThat(ExecutionContextHolder.require()).isEqualTo(request);
                        assertThat(Context.current().get(EXTRA)).isEqualTo("captured");
                        assertThat(MDC.get("actor_id")).isEqualTo("running");
                        interrupted.countDown();
                        await(finish);
                        assertThat(ExecutionContextHolder.require()).isEqualTo(request);
                        throw expected;
                    }
                    return null;
                }));
            }
            await(entered);
            assertThat(running.cancel(true)).isTrue();
            await(interrupted);
            assertThat(running.isDone()).isTrue();
            assertThat(events.list).isEmpty();
            finish.countDown();
            pool.submit(LoggingTasksTest::assertClean).get(5, TimeUnit.SECONDS);
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getLevel().toString()).isEqualTo("INFO");
        } finally {
            finish.countDown();
        }
    }

    @Test
    void virtualThreadAndCallerRunsObserveBodyAndRestore() throws Exception {
        ExecutionContext request = identity("virtual", "one");
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
                var ignored = ExecutionContextHolder.open(request); var trace = TRACE.makeCurrent()) {
            assertThat(virtual.submit(LoggingTasks.bindCallable("virtual", () -> {
                assertThat(Thread.currentThread().isVirtual()).isTrue();
                assertThat(ExecutionContextHolder.require()).isEqualTo(request);
                assertThat(Context.current().get(EXTRA)).isEqualTo("captured");
                return 42;
            })).get(5, TimeUnit.SECONDS)).isEqualTo(42);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (ThreadPoolExecutor caller = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy())) {
                caller.execute(() -> { entered.countDown(); await(release); });
                await(entered);
                Thread submitting = Thread.currentThread();
                try {
                    caller.execute(LoggingTasks.bindRunnable("caller-runs", () -> {
                        assertThat(Thread.currentThread()).isSameAs(submitting);
                        assertThat(ExecutionContextHolder.require()).isEqualTo(request);
                        assertThat(MDC.get("actor_id")).isEqualTo("one");
                    }));
                    assertThat(Context.current()).isSameAs(TRACE);
                    assertThat(MDC.get("actor_id")).isNull();
                } finally {
                    release.countDown();
                }
            }
        }
        assertThat(events.list).hasSize(2);
    }

    @Test
    void registrationBoundCallbacksKeepCompleteContextAndDoNotLogTasks() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> result;
        ExecutionContext request = identity("callback", "registered");
        try (var ignored = ExecutionContextHolder.open(request); var trace = TRACE.makeCurrent()) {
            LoggingContextSnapshot snapshot = LoggingContextSnapshot.capture();
            result = source.thenApply(snapshot.bindFunction(value -> {
                assertThat(ExecutionContextHolder.require()).isEqualTo(request);
                assertThat(Context.current()).isSameAs(TRACE);
                assertThat(MDC.get("actor_id")).isEqualTo("registered");
                return value + "-done";
            }));
            snapshot.bindRunnable(() -> assertThat(Context.current()).isSameAs(TRACE)).run();
            assertThat(snapshot.bindCallable(() -> 1).call()).isEqualTo(1);
            assertThat(snapshot.bindSupplier(() -> 2).get()).isEqualTo(2);
            snapshot.bindConsumer(value -> assertThat(value).isEqualTo(3)).accept(3);
            snapshot.bindBiConsumer((a, b) -> assertThat(a).isEqualTo(b)).accept(4, 4);
            assertThat(snapshot.bindBiFunction((Integer a, Integer b) -> a + b).apply(2, 3)).isEqualTo(5);
        }
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> {
                source.complete("value");
                assertClean();
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("value-done");
        assertThat(events.list).isEmpty();
    }

    @Test
    void originalCheckedExceptionAndErrorReachFutureAndCaller() throws Exception {
        IOException failure = new IOException("original");
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> failed = pool.submit(LoggingTasks.bindCallable("checked", () -> { throw failure; }));
            assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).satisfies(error -> assertThat(error.getCause()).isSameAs(failure));
            pool.submit(LoggingTasksTest::assertClean).get(5, TimeUnit.SECONDS);
        }
        AssertionError error = new AssertionError("original");
        assertThatThrownBy(() -> LoggingTasks.bindRunnable("error", () -> { throw error; }).run()).isSameAs(error);
        assertClean();
        assertThat(events.list).hasSize(2);
    }

    private static ExecutionContext identity(String tenant, String actorId) {
        Actor actor = new Actor(ActorType.USER, actorId, Set.of("read"));
        return new ExecutionContext(new TenantId(tenant), actor, Initiator.from(actor), "c-" + actorId);
    }

    private static void assertClean() {
        assertThat(ExecutionContextHolder.current()).isEmpty();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(Context.current().get(EXTRA)).isNull();
        assertThat(MDC.get("actor_id")).isNull();
        assertThat(MDC.get("trace_id")).isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
