package io.github.ande1922.moduvera.verification.springtask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.context.spring.ExecutionContextTaskDecorator;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class SpringTaskContextConsumerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AnnotationConfigApplicationContext applicationContext;

    @BeforeEach
    void openApplicationContext() {
        applicationContext = new AnnotationConfigApplicationContext(SpringTaskContextConsumer.class);
    }

    @AfterEach
    void closeApplicationContext() {
        if (applicationContext != null) {
            applicationContext.close();
        }
    }

    @Test
    void selectedAsyncProxyPropagatesEachFullIdentityAfterItsParentScopeExits() throws Exception {
        ThreadPoolTaskExecutor selected = selectedExecutor();
        AsyncContextProbe probe = applicationContext.getBean(AsyncContextProbe.class);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        selected.execute(() -> {
            workerStarted.countDown();
            await(releaseWorker);
        });
        await(workerStarted);

        ExecutionContext first = tenantContext(
                "tenant-a", "actor-a", "initiator-a", "correlation-a", "orders:read");
        CompletableFuture<Optional<ExecutionContext>> delayed;
        try (var ignored = ExecutionContextHolder.open(first)) {
            delayed = probe.throughPropagatingExecutor();
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
        releaseWorker.countDown();
        assertThat(delayed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).contains(first);

        ExecutionContext sameTenantSecondRequest = tenantContext(
                "tenant-a", "actor-b", "initiator-b", "correlation-b", "orders:write");
        ExecutionContext platform = platformContext("platform-actor", "platform-initiator", "correlation-platform");

        assertThat(inContext(first, probe::throughPropagatingExecutor)).contains(first);
        assertThat(inContext(sameTenantSecondRequest, probe::throughPropagatingExecutor))
                .contains(sameTenantSecondRequest);
        assertThat(inContext(platform, probe::throughPropagatingExecutor)).contains(platform);
        assertThat(probe.throughPropagatingExecutor().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEmpty();

        Future<Optional<ExecutionContext>> workerAfterTasks =
                selected.getThreadPoolExecutor().submit(ExecutionContextHolder::current);
        assertThat(workerAfterTasks.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEmpty();
    }

    @Test
    void unselectedAsyncExecutorIsNotDecoratedGlobally() throws Exception {
        AsyncContextProbe probe = applicationContext.getBean(AsyncContextProbe.class);
        ExecutionContext caller = tenantContext(
                "tenant-a", "actor", "initiator", "correlation", "orders:read");

        Optional<ExecutionContext> observed = inContext(caller, probe::throughPlainExecutor);

        assertThat(observed).isEmpty();
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void absentSubmissionMasksAndThenRestoresAReusedWorkersExistingIdentity() throws Exception {
        ExecutionContext workerIdentity = platformContext("worker", "worker-init", "worker-correlation");
        AtomicReference<Optional<ExecutionContext>> afterWorkerLoop = new AtomicReference<>();
        AtomicReference<Optional<ExecutionContext>> afterWorkerScope = new AtomicReference<>();
        ThreadPoolTaskExecutor selected = new ThreadPoolTaskExecutor();
        selected.setCorePoolSize(1);
        selected.setMaxPoolSize(1);
        selected.setQueueCapacity(4);
        selected.setTaskDecorator(new ExecutionContextTaskDecorator());
        selected.setThreadFactory(workerLoop -> new Thread(() -> {
            try (var ignored = ExecutionContextHolder.open(workerIdentity)) {
                workerLoop.run();
                afterWorkerLoop.set(ExecutionContextHolder.current());
            }
            afterWorkerScope.set(ExecutionContextHolder.current());
        }, "pre-context-worker"));
        selected.initialize();

        assertThat(selected.submit(ExecutionContextHolder::current)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEmpty();
        assertThat(selected.submit(ExecutionContextHolder::current)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEmpty();

        selected.shutdown();
        assertThat(selected.getThreadPoolExecutor()
                        .awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();

        assertThat(afterWorkerLoop.get()).contains(workerIdentity);
        assertThat(afterWorkerScope.get()).isEmpty();
    }

    @Test
    void springFuturesPreserveValuesFailuresRejectionAndCancellationUntilActualExit() throws Exception {
        ThreadPoolTaskExecutor selected = selectedExecutor();
        assertThat(selected.submit(() -> "value").get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo("value");

        IllegalArgumentException taskFailure = new IllegalArgumentException("future failure");
        Future<String> failed = selected.submit(() -> {
            throw taskFailure;
        });
        assertThatThrownBy(() -> failed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isSameAs(taskFailure);

        AsyncContextProbe probe = applicationContext.getBean(AsyncContextProbe.class);
        CompletableFuture<Void> asyncFailure = probe.fail("async failure");
        assertThatThrownBy(() -> asyncFailure.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("async failure");

        verifyCancellationBeforeStart(selected);
        verifyRunningCancellationClosesAtActualExit(selected);
        verifyGetTimeoutLeavesCleanupWithTheRunningTask(selected);
        verifyNativeRejection();
    }

    @Test
    void callerRunsExecutesInlineAndRestoresTheSubmittingThread() throws Exception {
        ThreadPoolTaskExecutor callerRuns = new ThreadPoolTaskExecutor();
        callerRuns.setCorePoolSize(1);
        callerRuns.setMaxPoolSize(1);
        callerRuns.setQueueCapacity(0);
        callerRuns.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        callerRuns.setTaskDecorator(new ExecutionContextTaskDecorator());
        callerRuns.initialize();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try {
            callerRuns.execute(() -> {
                workerStarted.countDown();
                await(releaseWorker);
            });
            await(workerStarted);
            ExecutionContext caller = platformContext("caller", "initiator", "caller-runs");
            IllegalStateException failure = new IllegalStateException("inline failure");

            try (var ignored = ExecutionContextHolder.open(caller)) {
                assertThatThrownBy(() -> callerRuns.execute(() -> {
                            assertThat(ExecutionContextHolder.require()).isEqualTo(caller);
                            throw failure;
                        }))
                        .isSameAs(failure);
                assertThat(ExecutionContextHolder.require()).isEqualTo(caller);
            }
            assertThat(ExecutionContextHolder.current()).isEmpty();
        } finally {
            releaseWorker.countDown();
            callerRuns.shutdown();
        }
    }

    @Test
    void closingTheApplicationContextKeepsSpringExecutorLifecycleOwnership() {
        ThreadPoolTaskExecutor selected = selectedExecutor();
        ThreadPoolTaskExecutor plain = applicationContext.getBean(
                SpringTaskContextConsumer.PLAIN_EXECUTOR, ThreadPoolTaskExecutor.class);

        applicationContext.close();
        applicationContext = null;

        assertThat(selected.getThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(plain.getThreadPoolExecutor().isShutdown()).isTrue();
    }

    private void verifyCancellationBeforeStart(ThreadPoolTaskExecutor executor) throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        executor.execute(() -> {
            workerStarted.countDown();
            await(releaseWorker);
        });
        await(workerStarted);

        Future<Void> cancelled = executor.submit(() -> {
            ran.set(true);
            return null;
        });
        assertThat(cancelled.cancel(false)).isTrue();
        releaseWorker.countDown();
        assertThatThrownBy(cancelled::get).isInstanceOf(CancellationException.class);
        assertThat(executor.getThreadPoolExecutor()
                        .submit(ExecutionContextHolder::current)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEmpty();
        assertThat(ran).isFalse();
    }

    private void verifyRunningCancellationClosesAtActualExit(ThreadPoolTaskExecutor executor)
            throws Exception {
        ExecutionContext taskContext = tenantContext(
                "tenant-cancel", "actor-cancel", "initiator-cancel", "correlation-cancel", "tasks:run");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch releaseActualExit = new CountDownLatch(1);
        AtomicReference<Optional<ExecutionContext>> afterCancellation = new AtomicReference<>();
        Future<Void> running;

        try (var ignored = ExecutionContextHolder.open(taskContext)) {
            running = executor.submit(() -> {
                started.countDown();
                try {
                    awaitInterruptibly(releaseActualExit);
                } catch (InterruptedException expected) {
                    afterCancellation.set(ExecutionContextHolder.current());
                    interrupted.countDown();
                    await(releaseActualExit);
                }
                assertThat(ExecutionContextHolder.require()).isEqualTo(taskContext);
                return null;
            });
        }

        await(started);
        assertThat(running.cancel(true)).isTrue();
        await(interrupted);
        assertThat(afterCancellation.get()).contains(taskContext);
        assertThatThrownBy(running::get).isInstanceOf(CancellationException.class);

        releaseActualExit.countDown();
        assertThat(executor.getThreadPoolExecutor()
                        .submit(ExecutionContextHolder::current)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEmpty();
    }

    private void verifyGetTimeoutLeavesCleanupWithTheRunningTask(ThreadPoolTaskExecutor executor)
            throws Exception {
        ExecutionContext taskContext = tenantContext(
                "tenant-timeout", "actor-timeout", "initiator-timeout", "correlation-timeout", "tasks:run");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch timeoutObserved = new CountDownLatch(1);
        CountDownLatch contextRecorded = new CountDownLatch(1);
        CountDownLatch releaseActualExit = new CountDownLatch(1);
        AtomicReference<Optional<ExecutionContext>> afterTimeout = new AtomicReference<>();
        Future<Void> running;

        try (var ignored = ExecutionContextHolder.open(taskContext)) {
            running = executor.submit(() -> {
                started.countDown();
                await(timeoutObserved);
                afterTimeout.set(ExecutionContextHolder.current());
                contextRecorded.countDown();
                await(releaseActualExit);
                return null;
            });
        }

        await(started);
        assertThatThrownBy(() -> running.get(1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        timeoutObserved.countDown();
        await(contextRecorded);
        assertThat(afterTimeout.get()).contains(taskContext);

        releaseActualExit.countDown();
        assertThat(running.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isNull();
        assertThat(executor.getThreadPoolExecutor()
                        .submit(ExecutionContextHolder::current)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEmpty();
    }

    private static void verifyNativeRejection() {
        ThreadPoolTaskExecutor rejected = new ThreadPoolTaskExecutor();
        rejected.setCorePoolSize(1);
        rejected.setTaskDecorator(new ExecutionContextTaskDecorator());
        rejected.initialize();
        rejected.shutdown();

        assertThatThrownBy(() -> rejected.execute(() -> {}))
                .isInstanceOf(TaskRejectedException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    private ThreadPoolTaskExecutor selectedExecutor() {
        return applicationContext.getBean(
                SpringTaskContextConsumer.PROPAGATING_EXECUTOR, ThreadPoolTaskExecutor.class);
    }

    private static Optional<ExecutionContext> inContext(
            ExecutionContext context,
            java.util.function.Supplier<CompletableFuture<Optional<ExecutionContext>>> invocation)
            throws Exception {
        CompletableFuture<Optional<ExecutionContext>> future;
        try (var ignored = ExecutionContextHolder.open(context)) {
            future = invocation.get();
        }
        return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static ExecutionContext tenantContext(
            String tenant,
            String actor,
            String initiator,
            String correlation,
            String permission) {
        return new ExecutionContext(
                ExecutionScope.tenant(new TenantId(tenant)),
                new Actor(ActorType.USER, actor, Set.of(permission)),
                new Initiator(ActorType.SERVICE, initiator),
                correlation);
    }

    private static ExecutionContext platformContext(String actor, String initiator, String correlation) {
        return new ExecutionContext(
                ExecutionScope.platform(),
                new Actor(ActorType.SERVICE, actor, Set.of("platform:run")),
                new Initiator(ActorType.USER, initiator),
                correlation);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test coordination interrupted", exception);
        }
    }

    private static void awaitInterruptibly(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("timed out waiting for task release");
        }
    }
}
