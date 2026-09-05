package io.github.ande1922.moduvera.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundBiConsumer;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundBiFunction;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundCallable;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundConsumer;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundFunction;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundRunnable;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot.BoundSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutionContextBoundCallbackTest {

    private static final ExecutionContext REQUEST_A = tenantContext(
            "tenant-a", "service-a", "alice", "request-a");
    private static final ExecutionContext REQUEST_B = tenantContext(
            "tenant-a", "service-b", "bob", "request-b");
    private static final ExecutionContext WORKER = new ExecutionContext(
            ExecutionScope.platform(),
            new Actor(ActorType.SYSTEM, "worker"),
            new Initiator(ActorType.SYSTEM, "worker-origin"),
            "worker-correlation");
    private static final ExecutionContext PLATFORM_REQUEST = new ExecutionContext(
            ExecutionScope.platform(),
            new Actor(ActorType.USER, "platform-user"),
            new Initiator(ActorType.SERVICE, "edge"),
            "platform-request");

    @Test
    void exposesAllNamedJdkShapesWithOneFixedSnapshotAndRestoresTheCaller() throws Exception {
        ExecutionContextSnapshot snapshot = ExecutionContextSnapshot.of(PLATFORM_REQUEST);
        List<ExecutionContext> observed = new ArrayList<>();
        AtomicReference<String> consumed = new AtomicReference<>();
        AtomicReference<String> biConsumed = new AtomicReference<>();

        BoundRunnable runnable = snapshot.bindRunnable(() -> observed.add(ExecutionContextHolder.require()));
        BoundCallable<String> callable = snapshot.bindCallable(() -> {
            observed.add(ExecutionContextHolder.require());
            return "called";
        });
        BoundSupplier<String> supplier = snapshot.bindSupplier(() -> {
            observed.add(ExecutionContextHolder.require());
            return "supplied";
        });
        BoundFunction<String, String> function = snapshot.bindFunction(value -> {
            observed.add(ExecutionContextHolder.require());
            return value.toUpperCase();
        });
        BoundConsumer<String> consumer = snapshot.bindConsumer(value -> {
            observed.add(ExecutionContextHolder.require());
            consumed.set(value);
        });
        BoundBiFunction<String, String, String> biFunction = snapshot.bindBiFunction((first, second) -> {
            observed.add(ExecutionContextHolder.require());
            return first + second;
        });
        BoundBiConsumer<String, String> biConsumer = snapshot.bindBiConsumer((first, second) -> {
            observed.add(ExecutionContextHolder.require());
            biConsumed.set(first + second);
        });

        ExecutionContextHolder.run(WORKER, () -> {
            runnable.run();
            assertThat(supplier.get()).isEqualTo("supplied");
            assertThat(function.apply("value")).isEqualTo("VALUE");
            consumer.accept("consumed");
            assertThat(biFunction.apply("first", "second")).isEqualTo("firstsecond");
            biConsumer.accept("bi", "consumed");
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
        });
        assertThat(callable.call()).isEqualTo("called");

        assertThat(observed).containsOnly(PLATFORM_REQUEST).hasSize(7);
        assertThat(consumed.get()).isEqualTo("consumed");
        assertThat(biConsumed.get()).isEqualTo("biconsumed");
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void preservesOriginalFailuresAndExplicitAbsenceWhileRestoringWorkerIdentity() {
        RuntimeException runtimeFailure = new IllegalStateException("runtime");
        Error errorFailure = new AssertionError("error");
        Exception checkedFailure = new Exception("checked");
        BoundFunction<String, String> runtime = ExecutionContextSnapshot.of(REQUEST_A)
                .bindFunction(value -> {
                    throw runtimeFailure;
                });
        BoundSupplier<String> error = ExecutionContextSnapshot.of(REQUEST_A)
                .bindSupplier(() -> {
                    throw errorFailure;
                });
        BoundCallable<String> checked = ExecutionContextSnapshot.of(REQUEST_A)
                .bindCallable(() -> {
                    throw checkedFailure;
                });
        BoundSupplier<Optional<ExecutionContext>> absent =
                ExecutionContextSnapshot.captureAllowingAbsent().bindSupplier(ExecutionContextHolder::current);

        ExecutionContextHolder.run(WORKER, () -> {
            assertThatThrownBy(() -> runtime.apply("ignored")).isSameAs(runtimeFailure);
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
            assertThatThrownBy(error::get).isSameAs(errorFailure);
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
            assertThat(absent.get()).isEmpty();
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
        });
        assertThatThrownBy(checked::call).isSameAs(checkedFailure);
        assertThatThrownBy(ExecutionContextSnapshot::capture)
                .isInstanceOf(MissingExecutionContextException.class);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void completedFutureRunsInlineWithTheRegistrationContext() throws Exception {
        CompletableFuture<ExecutionContext> callback = ExecutionContextHolder.call(
                PLATFORM_REQUEST,
                () -> CompletableFuture.completedFuture("ready")
                        .thenApply(ExecutionContextSnapshot.capture()
                                .bindFunction(value -> ExecutionContextHolder.require())));

        assertThat(callback.get(2, TimeUnit.SECONDS)).isSameAs(PLATFORM_REQUEST);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void externalCompletionUsesEachRegistrationContextAfterParentScopesExit() throws Exception {
        CompletableFuture<String> firstSource = new CompletableFuture<>();
        CompletableFuture<String> secondSource = new CompletableFuture<>();
        CompletableFuture<ExecutionContext> first = registerObservation(firstSource, REQUEST_A);
        CompletableFuture<ExecutionContext> second = registerObservation(secondSource, REQUEST_B);
        ExecutorService worker = newContextWorker(WORKER);

        try {
            var completion = worker.submit(() -> {
                assertThat(firstSource.complete("first")).isTrue();
                assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
                assertThat(secondSource.complete("second")).isTrue();
                return ExecutionContextHolder.require();
            });

            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(REQUEST_A);
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(REQUEST_B);
            assertThat(completion.get(2, TimeUnit.SECONDS)).isSameAs(WORKER);
        } finally {
            stop(worker);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void specifiedAsyncExecutorUsesTheBoundContextAndSameExecutionCanRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException firstFailure = new IllegalStateException("retry");
        BoundFunction<String, ExecutionContext> callback = ExecutionContextHolder.call(
                REQUEST_A,
                () -> ExecutionContextSnapshot.capture().bindFunction(value -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw firstFailure;
                    }
                    return ExecutionContextHolder.require();
                }));
        ExecutorService worker = newContextWorker(WORKER);

        try {
            assertThatThrownBy(() -> ExecutionContextHolder.run(
                            REQUEST_B, () -> callback.apply("first")))
                    .isSameAs(firstFailure);
            assertThat(ExecutionContextHolder.current()).isEmpty();

            CompletableFuture<ExecutionContext> retried =
                    CompletableFuture.completedFuture("second").thenApplyAsync(callback, worker);
            assertThat(retried.get(2, TimeUnit.SECONDS)).isEqualTo(REQUEST_A);
            assertThat(worker.submit(ExecutionContextHolder::require).get(2, TimeUnit.SECONDS))
                    .isSameAs(WORKER);
        } finally {
            stop(worker);
        }
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void thenComposeBindingEndsWhenTheSynchronousCallbackReturns() throws Exception {
        AtomicReference<ExecutionContext> synchronousContext = new AtomicReference<>();
        ExecutorService worker = newContextWorker(WORKER);

        try {
            BoundFunction<String, CompletableFuture<Optional<ExecutionContext>>> compose =
                    ExecutionContextHolder.call(
                            REQUEST_A,
                            () -> ExecutionContextSnapshot.capture().bindFunction(value -> {
                                synchronousContext.set(ExecutionContextHolder.require());
                                return CompletableFuture.supplyAsync(
                                        ExecutionContextHolder::current, worker);
                            }));
            CompletableFuture<Optional<ExecutionContext>> composed =
                    CompletableFuture.completedFuture("start").thenCompose(compose);

            assertThat(composed.get(2, TimeUnit.SECONDS)).contains(WORKER);
            assertThat(synchronousContext.get()).isEqualTo(REQUEST_A);
        } finally {
            stop(worker);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private static CompletableFuture<ExecutionContext> registerObservation(
            CompletableFuture<String> source, ExecutionContext context) {
        return ExecutionContextHolder.call(
                context,
                () -> source.thenApply(ExecutionContextSnapshot.capture()
                        .bindFunction(value -> ExecutionContextHolder.require())));
    }

    private static ExecutorService newContextWorker(ExecutionContext context) {
        return Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
                .name("bound-callback-worker")
                .unstarted(() -> ExecutionContextHolder.run(context, task)));
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    private static ExecutionContext tenantContext(
            String tenant, String actor, String initiator, String correlation) {
        return new ExecutionContext(
                ExecutionScope.tenant(new TenantId(tenant)),
                new Actor(ActorType.SERVICE, actor),
                new Initiator(ActorType.USER, initiator),
                correlation);
    }
}
