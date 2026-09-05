package io.github.ande1922.moduvera.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundCallable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CloseResource")
class ContextExecutorsTest {

    private static final ExecutionContext REQUEST_A = tenantContext(
            "tenant-a", ActorType.USER, "alice", ActorType.USER, "alice", "request-a");
    private static final ExecutionContext SAME_TENANT_OTHER_IDENTITY = tenantContext(
            "tenant-a", ActorType.SERVICE, "catalog", ActorType.USER, "bob", "request-a-2");
    private static final ExecutionContext REQUEST_B = tenantContext(
            "tenant-b", ActorType.USER, "bob", ActorType.USER, "bob", "request-b");
    private static final ExecutionContext PLATFORM = new ExecutionContext(
            ExecutionScope.platform(),
            new Actor(ActorType.SERVICE, "platform-admin"),
            new Initiator(ActorType.USER, "operator"),
            "platform-request");
    private static final ExecutionContext WORKER = tenantContext(
            "worker-tenant", ActorType.SYSTEM, "worker", ActorType.SYSTEM, "worker", "worker");

    @Test
    void capturesEachSubmissionRatherThanDecoratorConstructionOnAReusedWorker() throws Exception {
        ExecutorService raw = newContextWorker();
        ExecutorService decorated = ExecutionContextHolder.call(
                REQUEST_B, () -> ContextExecutors.propagating(raw));

        try {
            Future<ExecutionContext> first = ExecutionContextHolder.call(
                    REQUEST_A, () -> decorated.submit(ExecutionContextHolder::require));
            Future<ExecutionContext> second = ExecutionContextHolder.call(
                    SAME_TENANT_OTHER_IDENTITY,
                    () -> decorated.submit(ExecutionContextHolder::require));
            Future<ExecutionContext> platform = ExecutionContextHolder.call(
                    PLATFORM, () -> decorated.submit(ExecutionContextHolder::require));
            Future<Optional<ExecutionContext>> absent =
                    decorated.submit(ExecutionContextHolder::current);

            assertThat(first.get(2, TimeUnit.SECONDS)).isSameAs(REQUEST_A);
            assertThat(second.get(2, TimeUnit.SECONDS)).isSameAs(SAME_TENANT_OTHER_IDENTITY);
            assertThat(platform.get(2, TimeUnit.SECONDS)).isSameAs(PLATFORM);
            assertThat(absent.get(2, TimeUnit.SECONDS)).isEmpty();
            assertThat(raw.submit(ExecutionContextHolder::require).get(2, TimeUnit.SECONDS))
                    .isSameAs(WORKER);
        } finally {
            stop(raw);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void propagatesThroughVirtualInlineAndCallerRunsExecution() throws Exception {
        try (ExecutorService rawVirtual = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutorService virtual = ContextExecutors.propagating(rawVirtual);
            ThreadObservation observation = ExecutionContextHolder.call(
                            REQUEST_A,
                            () -> virtual.submit(() -> new ThreadObservation(
                                    Thread.currentThread().isVirtual(), ExecutionContextHolder.require())))
                    .get(2, TimeUnit.SECONDS);
            assertThat(observation).isEqualTo(new ThreadObservation(true, REQUEST_A));
        }

        Executor inline = ContextExecutors.propagating((Executor) Runnable::run);
        AtomicReference<ExecutionContext> inlineContext = new AtomicReference<>();
        ExecutionContextHolder.run(PLATFORM, () -> inline.execute(
                () -> inlineContext.set(ExecutionContextHolder.require())));
        assertThat(inlineContext).hasValue(PLATFORM);
        RuntimeException inlineFailure = new IllegalStateException("inline");
        assertThatThrownBy(() -> ExecutionContextHolder.run(
                        PLATFORM, () -> inline.execute(() -> {
                            throw inlineFailure;
                        })))
                .isSameAs(inlineFailure);
        assertThat(ExecutionContextHolder.current()).isEmpty();

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor callerRunsDelegate = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            callerRunsDelegate.execute(() -> await(workerStarted, releaseWorker));
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Executor callerRuns = ContextExecutors.propagating((Executor) callerRunsDelegate);
            AtomicReference<Thread> executionThread = new AtomicReference<>();
            AtomicReference<ExecutionContext> callerRunsContext = new AtomicReference<>();
            Thread submittingThread = Thread.currentThread();

            ExecutionContextHolder.run(REQUEST_B, () -> callerRuns.execute(() -> {
                executionThread.set(Thread.currentThread());
                callerRunsContext.set(ExecutionContextHolder.require());
            }));

            assertThat(executionThread).hasValue(submittingThread);
            assertThat(callerRunsContext).hasValue(REQUEST_B);
            assertThat(ExecutionContextHolder.current()).isEmpty();
        } finally {
            releaseWorker.countDown();
            stop(callerRunsDelegate);
        }
    }

    @Test
    void preservesSubmitAndBulkResultsFailuresTimeoutsAndRejection() throws Exception {
        verifyTaskOutcomes(Executors.newSingleThreadExecutor(), false);
        verifyTaskOutcomes(Executors.newSingleThreadExecutor(), true);

        assertThat(reject(Executors.newSingleThreadExecutor(), false))
                .isExactlyInstanceOf(RejectedExecutionException.class);
        assertThat(reject(Executors.newSingleThreadExecutor(), true))
                .isExactlyInstanceOf(RejectedExecutionException.class);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void propagatesAndRestoresContextForEveryBulkSubmissionOverload() throws Exception {
        ExecutorService raw = newContextWorker();
        ExecutorService decorated = ContextExecutors.propagating(raw);
        try {
            List<Future<ExecutionContext>> all;
            try (var ignored = ExecutionContextHolder.open(REQUEST_A)) {
                all = decorated.invokeAll(
                        List.of(ExecutionContextHolder::require, ExecutionContextHolder::require));
            }
            assertThat(all.get(0).get(2, TimeUnit.SECONDS)).isSameAs(REQUEST_A);
            assertThat(all.get(1).get(2, TimeUnit.SECONDS)).isSameAs(REQUEST_A);
            assertWorkerRestored(raw);

            List<Future<ExecutionContext>> timedAll;
            try (var ignored = ExecutionContextHolder.open(PLATFORM)) {
                timedAll = decorated.invokeAll(
                        List.of(ExecutionContextHolder::require), 2, TimeUnit.SECONDS);
            }
            assertThat(timedAll).singleElement().satisfies(future -> {
                assertThat(future.isCancelled()).isFalse();
                assertThat(future.get(2, TimeUnit.SECONDS)).isSameAs(PLATFORM);
            });
            assertWorkerRestored(raw);

            ExecutionContext any;
            try (var ignored = ExecutionContextHolder.open(SAME_TENANT_OTHER_IDENTITY)) {
                any = decorated.invokeAny(List.of(ExecutionContextHolder::require));
            }
            assertThat(any).isSameAs(SAME_TENANT_OTHER_IDENTITY);
            assertWorkerRestored(raw);

            Optional<ExecutionContext> absent = decorated.invokeAny(
                    List.of(ExecutionContextHolder::current), 2, TimeUnit.SECONDS);
            assertThat(absent).isEmpty();
            assertWorkerRestored(raw);
        } finally {
            stop(raw);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void cancellationBeforeStartNeverOpensTheCapturedScope() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean taskRan = new AtomicBoolean();
        ExecutorService raw = newContextWorker();
        ExecutorService decorated = ContextExecutors.propagating(raw);
        try {
            raw.execute(() -> await(workerStarted, releaseWorker));
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> cancelled = ExecutionContextHolder.call(
                    REQUEST_A, () -> decorated.submit(() -> taskRan.set(true)));

            assertThat(cancelled.cancel(false)).isTrue();
            releaseWorker.countDown();
            assertThatThrownBy(() -> cancelled.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(raw.submit(ExecutionContextHolder::require).get(2, TimeUnit.SECONDS))
                    .isSameAs(WORKER);
            assertThat(taskRan).isFalse();
        } finally {
            releaseWorker.countDown();
            stop(raw);
        }
    }

    @Test
    void timeoutAndRunningCancellationLeaveCleanupToTheActualDelegateExit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch sampleAfterTimeout = new CountDownLatch(1);
        CountDownLatch sampledAfterTimeout = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicReference<ExecutionContext> afterTimeout = new AtomicReference<>();
        AtomicReference<ExecutionContext> afterInterruption = new AtomicReference<>();
        ExecutorService raw = newContextWorker();
        ExecutorService decorated = ContextExecutors.propagating(raw);

        Future<?> running = ExecutionContextHolder.call(REQUEST_A, () -> decorated.submit(() -> {
            started.countDown();
            try {
                assertThat(sampleAfterTimeout.await(2, TimeUnit.SECONDS)).isTrue();
                afterTimeout.set(ExecutionContextHolder.require());
                sampledAfterTimeout.countDown();
                try {
                    releaseDelegate.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException cancellation) {
                    afterInterruption.set(ExecutionContextHolder.require());
                    interrupted.countDown();
                    assertThat(releaseDelegate.await(2, TimeUnit.SECONDS)).isTrue();
                }
            } catch (InterruptedException unexpected) {
                Thread.currentThread().interrupt();
                throw new AssertionError("unexpected interruption", unexpected);
            } finally {
                exited.countDown();
            }
        }));

        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> running.get(1, TimeUnit.NANOSECONDS))
                    .isInstanceOf(TimeoutException.class);
            sampleAfterTimeout.countDown();
            assertThat(sampledAfterTimeout.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(afterTimeout).hasValue(REQUEST_A);
            assertThat(exited.getCount()).isEqualTo(1);

            assertThat(running.cancel(true)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(afterInterruption).hasValue(REQUEST_A);
            assertThat(exited.getCount()).isEqualTo(1);

            releaseDelegate.countDown();
            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(raw.submit(ExecutionContextHolder::require).get(2, TimeUnit.SECONDS))
                    .isSameAs(WORKER);
        } finally {
            sampleAfterTimeout.countDown();
            running.cancel(true);
            releaseDelegate.countDown();
            stop(raw);
        }
    }

    @Test
    void explicitBindingWinsThroughDecoratorAndNativeFutureTaskNesting() throws Exception {
        ExecutorService raw = newContextWorker();
        ExecutorService decorated = ContextExecutors.propagating(raw);
        try {
            BoundCallable<ExecutionContext> bound = ExecutionContextHolder.call(
                    REQUEST_A,
                    () -> ExecutionContextSnapshot.capture().bindCallable(ExecutionContextHolder::require));
            Future<ExecutionContext> result = ExecutionContextHolder.call(
                    REQUEST_B, () -> decorated.submit(bound));

            assertThat(result.get(2, TimeUnit.SECONDS)).isSameAs(REQUEST_A);
            assertThat(raw.submit(ExecutionContextHolder::require).get(2, TimeUnit.SECONDS))
                    .isSameAs(WORKER);
        } finally {
            stop(raw);
        }
    }

    @Test
    void shutdownNowReturnsTheDelegatesQueuedFutureAndItRemainsRunnable() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor raw = newContextThreadPool();
        ExecutorService decorated = ContextExecutors.propagating(raw);
        raw.execute(() -> await(workerStarted, releaseWorker));
        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        Future<ExecutionContext> queued = ExecutionContextHolder.call(
                REQUEST_A, () -> decorated.submit(ExecutionContextHolder::require));
        Runnable delegateQueueEntry = raw.getQueue().element();

        List<Runnable> neverStarted = decorated.shutdownNow();
        releaseWorker.countDown();
        assertThat(raw.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(neverStarted).containsExactly(delegateQueueEntry);
        assertThat(delegateQueueEntry).isSameAs(queued);

        ExecutionContextHolder.run(WORKER, delegateQueueEntry);
        assertThat(queued.get(2, TimeUnit.SECONDS)).isSameAs(REQUEST_A);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void delegatesOrderlyShutdownAwaitTerminationAndClose() throws Exception {
        ExecutorService raw = Executors.newSingleThreadExecutor();
        ExecutorService decorated = ContextExecutors.propagating(raw);
        Future<String> result = decorated.submit(() -> "done");
        decorated.shutdown();

        assertThat(decorated.isShutdown()).isTrue();
        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(decorated.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(decorated.isTerminated()).isTrue();

        ExecutorService closeDelegate = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService closeDecorator = ContextExecutors.propagating(closeDelegate);
        assertThat(closeDecorator.submit(() -> "closed").get(2, TimeUnit.SECONDS))
                .isEqualTo("closed");
        closeDecorator.close();
        assertThat(closeDelegate.isShutdown()).isTrue();
        assertThat(closeDelegate.isTerminated()).isTrue();
    }

    private static void verifyTaskOutcomes(ExecutorService raw, boolean decorate) throws Exception {
        ExecutorService executor = decorate ? ContextExecutors.propagating(raw) : raw;
        IllegalStateException failure = new IllegalStateException("boom");
        CountDownLatch releaseTimedTask = new CountDownLatch(1);
        try {
            Future<Integer> value = ExecutionContextHolder.call(
                    REQUEST_A, () -> executor.submit(() -> 42));
            Future<String> runnableValue = ExecutionContextHolder.call(
                    REQUEST_A, () -> executor.submit(() -> {}, "runnable-result"));
            Future<Integer> failed = ExecutionContextHolder.call(
                    REQUEST_A, () -> executor.submit(() -> {
                        throw failure;
                    }));

            assertThat(value.get(2, TimeUnit.SECONDS)).isEqualTo(42);
            assertThat(runnableValue.get(2, TimeUnit.SECONDS)).isEqualTo("runnable-result");
            assertThatThrownBy(() -> failed.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(failure);

            Callable<Integer> timedTask = () -> {
                releaseTimedTask.await(2, TimeUnit.SECONDS);
                return 5;
            };
            List<Future<Integer>> all;
            List<Future<Integer>> allWithFailure;
            int any;
            List<Future<Integer>> timed;
            try (var ignored = ExecutionContextHolder.open(REQUEST_A)) {
                all = executor.invokeAll(List.of(() -> 1, () -> 2));
                allWithFailure = executor.invokeAll(List.of(
                        () -> 1,
                        () -> {
                            throw failure;
                        }));
                any = executor.invokeAny(List.of(() -> 3, () -> 4));
                assertThatThrownBy(() -> executor.invokeAny(List.of(() -> {
                            throw failure;
                        })))
                        .isInstanceOf(ExecutionException.class)
                        .hasCause(failure);
                assertThatThrownBy(() -> executor.invokeAny(
                                List.of(timedTask), 1, TimeUnit.NANOSECONDS))
                        .isInstanceOf(TimeoutException.class);
                timed = executor.invokeAll(List.of(timedTask), 1, TimeUnit.NANOSECONDS);
            }
            assertThat(all.get(0).get()).isEqualTo(1);
            assertThat(all.get(1).get()).isEqualTo(2);
            assertThat(allWithFailure.get(0).get()).isEqualTo(1);
            assertThatThrownBy(() -> allWithFailure.get(1).get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(failure);
            assertThat(any).isIn(3, 4);
            assertThat(timed).singleElement().satisfies(future -> assertThat(future.isCancelled())
                    .isTrue());
        } finally {
            releaseTimedTask.countDown();
            stop(raw);
        }
    }

    private static RejectedExecutionException reject(ExecutorService raw, boolean decorate) {
        ExecutorService executor = decorate ? ContextExecutors.propagating(raw) : raw;
        raw.shutdown();
        try {
            ExecutionContextHolder.run(REQUEST_A, () -> executor.execute(() -> {}));
            throw new AssertionError("shutdown executor accepted a task");
        } catch (RejectedExecutionException rejected) {
            return rejected;
        }
    }

    private static ExecutorService newContextWorker() {
        return Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
                .name("context-executor-worker")
                .unstarted(() -> ExecutionContextHolder.run(WORKER, task)));
    }

    private static ThreadPoolExecutor newContextThreadPool() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                task -> Thread.ofPlatform()
                        .name("context-executor-worker")
                        .unstarted(() -> ExecutionContextHolder.run(WORKER, task)));
    }

    private static void await(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    private static void assertWorkerRestored(ExecutorService raw) throws Exception {
        assertThat(raw.submit(ExecutionContextHolder::require).get(2, TimeUnit.SECONDS))
                .isSameAs(WORKER);
    }

    private static ExecutionContext tenantContext(
            String tenant,
            ActorType actorType,
            String actor,
            ActorType initiatorType,
            String initiator,
            String correlation) {
        return new ExecutionContext(
                ExecutionScope.tenant(new TenantId(tenant)),
                new Actor(actorType, actor),
                new Initiator(initiatorType, initiator),
                correlation);
    }

    private record ThreadObservation(boolean virtual, ExecutionContext context) {}
}
