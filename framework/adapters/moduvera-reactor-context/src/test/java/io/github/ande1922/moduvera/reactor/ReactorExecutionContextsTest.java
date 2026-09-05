package io.github.ande1922.moduvera.reactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.context.TenantId;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

class ReactorExecutionContextsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ExecutionContext REQUEST = tenant("tenant-a", "alice", "caller", "request-a");
    private static final ExecutionContext WORKER = tenant("worker", "worker", "system", "worker-0");

    @Test
    void capturesMonoAndFluxAtTemplateCallBeforeParentScopeExits() throws Exception {
        try (var worker = new ForeignContextWorker(WORKER)) {
            Mono<ExecutionContext> mono;
            Flux<ExecutionContext> flux;
            try (var ignored = ExecutionContextHolder.open(REQUEST)) {
                mono = ReactorExecutionContexts.propagate(ReactorExecutionContexts.mapInContext(
                        Mono.just(1).publishOn(worker.scheduler()), ignoredValue -> assertCurrent(REQUEST)));
                flux = ReactorExecutionContexts.propagate(ReactorExecutionContexts.mapInContext(
                        Flux.just(1, 2).publishOn(worker.scheduler()), ignoredValue -> assertCurrent(REQUEST)));
            }

            assertContext(mono.block(TIMEOUT), REQUEST);
            assertThat(flux.collectList().block(TIMEOUT)).hasSize(2).allSatisfy(actual -> assertContext(actual, REQUEST));
            worker.assertRestored();
        }

        assertThatThrownBy(() -> ReactorExecutionContexts.propagate(Mono.just(1)))
            .isInstanceOf(MissingExecutionContextException.class);
        assertThatThrownBy(() -> ReactorExecutionContexts.propagate(Flux.just(1)))
            .isInstanceOf(MissingExecutionContextException.class);
    }

    @Test
    void requireReadsOnlyNativeContextAndRejectsWrongType() {
        try (var ignored = ExecutionContextHolder.open(WORKER)) {
            assertContext(ReactorExecutionContexts.require(Context.of(
                    ReactorExecutionContexts.EXECUTION_CONTEXT_KEY, REQUEST)), REQUEST);
            assertThatThrownBy(() -> ReactorExecutionContexts.require(Context.empty()))
                .isInstanceOf(MissingExecutionContextException.class);
            assertThatThrownBy(() -> ReactorExecutionContexts.require(Context.of(
                            ReactorExecutionContexts.EXECUTION_CONTEXT_KEY, "wrong")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong type");
            assertContext(ExecutionContextHolder.require(), WORKER);
        }
    }

    @Test
    void missingNativeKeyHidesForeignWorkerAndWrongTypeNeverInvokesMapper() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        try (var worker = new ForeignContextWorker(WORKER)) {
            Optional<ExecutionContext> inside = ReactorExecutionContexts.mapInContext(
                    Mono.just(1).publishOn(worker.scheduler()),
                    ignored -> {
                        assertThatThrownBy(ExecutionContextHolder::require)
                            .isInstanceOf(MissingExecutionContextException.class);
                        return ExecutionContextHolder.current();
                    }).block(TIMEOUT);

            assertThat(inside).isEmpty();
            worker.assertRestored();

            Mono<Integer> wrongType = ReactorExecutionContexts.mapInContext(
                            Mono.just(1).publishOn(worker.scheduler()), value -> {
                                invocations.incrementAndGet();
                                return value;
                            })
                    .contextWrite(context -> context.put(
                            ReactorExecutionContexts.EXECUTION_CONTEXT_KEY, "wrong"));
            assertThatThrownBy(() -> wrongType.block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong type");
            assertThat(invocations).hasValue(0);
            worker.assertRestored();
        }
    }

    @Test
    void reusableTemplateIsolatesConcurrentTenantPlatformAndSameTenantSubscribers() {
        ExecutionContext tenantA = tenant("tenant-a", "alice", "origin-a", "correlation-a");
        ExecutionContext tenantB = tenant("tenant-b", "bob", "origin-b", "correlation-b");
        ExecutionContext sameTenant = tenant("tenant-a", "carol", "origin-c", "correlation-c");
        ExecutionContext platform = platform("operator", "origin-platform", "correlation-platform");
        List<ExecutionContext> expected = List.of(tenantA, tenantB, sameTenant, platform);
        CountDownLatch allRunning = new CountDownLatch(expected.size());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<List<Observation>> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Scheduler scheduler = Schedulers.newParallel("context-subscriber", expected.size());
        try {
            Flux<Observation> template = ReactorExecutionContexts.mapInContext(
                            Flux.just(1).publishOn(scheduler),
                            ignored -> observeWhileConcurrent(allRunning, release))
                    .map(observation ->
                        new Observation(observation.inside(), ExecutionContextHolder.current()));
            Flux.merge(expected.stream()
                            .map(context -> ReactorExecutionContexts.withContext(context, template))
                            .toList())
                    .collectList()
                    .subscribe(
                            observations -> {
                                result.set(observations);
                                finished.countDown();
                            },
                            error -> {
                                failure.set(error);
                                finished.countDown();
                            });
            await(allRunning, "run all context subscribers concurrently");
            release.countDown();
            await(finished, "finish concurrent context subscribers");
        } finally {
            release.countDown();
            scheduler.dispose();
        }

        assertThat(failure.get()).isNull();
        List<Observation> observations = result.get();
        assertThat(observations).hasSize(expected.size());
        assertThat(observations).allSatisfy(observation -> assertThat(observation.after()).isEmpty());
        assertThat(observations.stream().map(Observation::inside).toList())
            .containsExactlyInAnyOrderElementsOf(expected);
        observations.forEach(observation -> assertFullContext(observation.inside()));
    }

    @Test
    void retryAndExceptionRestoreTheForeignWorkerBetweenMapperCalls() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<ExecutionContext> first = new AtomicReference<>();
        AtomicReference<ExecutionContext> second = new AtomicReference<>();
        AtomicReference<ExecutionContext> afterFailure = new AtomicReference<>();
        try (var worker = new ForeignContextWorker(WORKER)) {
            ExecutionContext restored = ReactorExecutionContexts.withContext(
                            REQUEST,
                            ReactorExecutionContexts.mapInContext(
                                    Mono.just(1).publishOn(worker.scheduler()),
                                    ignored -> {
                                        int attempt = attempts.incrementAndGet();
                                        ExecutionContext current = assertCurrent(REQUEST);
                                        if (attempt == 1) {
                                            first.set(current);
                                            throw new MappingFailure();
                                        }
                                        second.set(current);
                                        return current;
                                    })
                                    .doOnError(ignored -> afterFailure.set(ExecutionContextHolder.require()))
                                    .retry(1)
                                    .map(ignored -> ExecutionContextHolder.require()))
                    .block(TIMEOUT);

            assertContext(first.get(), REQUEST);
            assertContext(second.get(), REQUEST);
            assertContext(afterFailure.get(), WORKER);
            assertContext(restored, WORKER);
            assertThat(attempts).hasValue(2);
            worker.assertRestored();
        }
    }

    @Test
    void mapperFailureIsOriginalAndRestoresForeignWorker() throws Exception {
        AtomicReference<ExecutionContext> afterFailure = new AtomicReference<>();
        try (var worker = new ForeignContextWorker(WORKER)) {
            Mono<Integer> failing = ReactorExecutionContexts.withContext(
                    REQUEST,
                    ReactorExecutionContexts.<Integer, Integer>mapInContext(
                                    Mono.just(1).publishOn(worker.scheduler()),
                                    ignored -> {
                                        assertCurrent(REQUEST);
                                        throw new MappingFailure();
                                    })
                            .doOnError(ignored -> afterFailure.set(ExecutionContextHolder.require())));

            assertThatThrownBy(() -> failing.block(TIMEOUT)).isInstanceOf(MappingFailure.class);
            assertContext(afterFailure.get(), WORKER);
            worker.assertRestored();
        }
    }

    @Test
    void nativeMapOutsideTheTemplateDoesNotAutomaticallyRestoreHolder() throws Exception {
        try (var worker = new ForeignContextWorker(WORKER)) {
            ExecutionContext observed = ReactorExecutionContexts.withContext(
                            REQUEST,
                            Mono.just(1)
                                    .publishOn(worker.scheduler())
                                    .map(ignored -> ExecutionContextHolder.require()))
                    .block(TIMEOUT);

            assertContext(observed, WORKER);
            worker.assertRestored();
        }
    }

    @Test
    void cancellationDoesNotCleanStillRunningSynchronousMapper() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        CountDownLatch sampledAfterCancellation = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch mapperExited = new CountDownLatch(1);
        AtomicReference<ExecutionContext> afterCancellation = new AtomicReference<>();
        try (var worker = new ForeignContextWorker(WORKER)) {
            Mono<Integer> pipeline = ReactorExecutionContexts.withContext(
                    REQUEST,
                    ReactorExecutionContexts.mapInContext(
                            Mono.just(1).publishOn(worker.scheduler()), ignored -> {
                                try {
                                    entered.countDown();
                                    awaitDespiteCancellation(cancellationObserved, "observe cancellation");
                                    afterCancellation.set(assertCurrent(REQUEST));
                                    sampledAfterCancellation.countDown();
                                    awaitDespiteCancellation(release, "leave mapper");
                                    return 1;
                                } finally {
                                    mapperExited.countDown();
                                }
                            }));
            Disposable subscription = pipeline.subscribe();
            try {
                await(entered, "enter mapper");
                subscription.dispose();
                cancellationObserved.countDown();
                await(sampledAfterCancellation, "sample mapper context after cancellation");
                assertContext(afterCancellation.get(), REQUEST);
            } finally {
                release.countDown();
            }
            await(mapperExited, "exit cancelled mapper");
            worker.assertRestored();
        } finally {
            cancellationObserved.countDown();
            release.countDown();
        }
    }

    private static ExecutionContext assertCurrent(ExecutionContext expected) {
        ExecutionContext actual = ExecutionContextHolder.require();
        assertContext(actual, expected);
        return actual;
    }

    private static Observation observeWhileConcurrent(
            CountDownLatch allRunning, CountDownLatch release) {
        ExecutionContext current = ExecutionContextHolder.require();
        if (current.scope() instanceof ExecutionScope.Platform) {
            assertThatThrownBy(current::requireTenantId).isInstanceOf(IllegalStateException.class);
        } else {
            assertThat(current.requireTenantId()).isNotNull();
        }
        allRunning.countDown();
        await(release, "release concurrent context subscribers");
        return new Observation(current, Optional.empty());
    }

    private static void assertContext(ExecutionContext actual, ExecutionContext expected) {
        assertThat(actual.scope()).isEqualTo(expected.scope());
        assertThat(actual.actor()).isEqualTo(expected.actor());
        assertThat(actual.initiator()).isEqualTo(expected.initiator());
        assertThat(actual.correlationId()).isEqualTo(expected.correlationId());
    }

    private static void assertFullContext(ExecutionContext context) {
        assertThat(context.scope()).isNotNull();
        assertThat(context.actor()).isNotNull();
        assertThat(context.initiator()).isNotNull();
        assertThat(context.correlationId()).isNotBlank();
    }

    private static ExecutionContext tenant(
            String tenant, String actor, String initiator, String correlation) {
        return context(ExecutionScope.tenant(new TenantId(tenant)), actor, initiator, correlation);
    }

    private static ExecutionContext platform(String actor, String initiator, String correlation) {
        return context(ExecutionScope.platform(), actor, initiator, correlation);
    }

    private static ExecutionContext context(
            ExecutionScope scope, String actor, String initiator, String correlation) {
        return new ExecutionContext(
                scope,
                new Actor(ActorType.USER, actor, Set.of("orders:read")),
                new Initiator(ActorType.SERVICE, initiator),
                correlation);
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            assertThat(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("timed out waiting to " + operation)
                .isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to " + operation, exception);
        }
    }

    private static void awaitDespiteCancellation(CountDownLatch latch, String operation) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (latch.getCount() != 0 && System.nanoTime() < deadline) {
            try {
                latch.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                // Reactor cancellation may interrupt the scheduler task; keep this mapper running
                // until the test's explicit release so its Scope lifetime remains observable.
            }
        }
        assertThat(latch.getCount()).as("timed out waiting to " + operation).isZero();
    }

    private record Observation(ExecutionContext inside, Optional<ExecutionContext> after) {}

    private static final class MappingFailure extends RuntimeException {}

    private static final class ForeignContextWorker implements AutoCloseable {

        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Scheduler scheduler = Schedulers.fromExecutorService(executor);
        private final AtomicReference<ExecutionContextHolder.Scope> workerScope = new AtomicReference<>();
        private final ExecutionContext expected;

        private ForeignContextWorker(ExecutionContext context) throws Exception {
            this.expected = context;
            executor.submit(() -> workerScope.set(ExecutionContextHolder.open(context)))
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        private Scheduler scheduler() {
            return scheduler;
        }

        private void assertRestored() throws Exception {
            Future<ExecutionContext> probe = executor.submit(ExecutionContextHolder::require);
            assertContext(probe.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), expected);
        }

        @Override
        public void close() throws Exception {
            Future<?> cleanup = executor.submit(() -> workerScope.get().close());
            cleanup.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            scheduler.dispose();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
    }
}
