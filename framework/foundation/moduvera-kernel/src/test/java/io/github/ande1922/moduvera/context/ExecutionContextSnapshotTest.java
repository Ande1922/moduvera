package io.github.ande1922.moduvera.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutionContextSnapshotTest {

    private static final ExecutionContext TENANT_A = context("tenant-a", "alice", "corr-a");
    private static final ExecutionContext TENANT_A_SECOND_REQUEST = new ExecutionContext(
            new TenantId("tenant-a"),
            new Actor(ActorType.SERVICE, "catalog"),
            new Initiator(ActorType.USER, "bob"),
            "corr-a-2");
    private static final ExecutionContext TENANT_B = context("tenant-b", "bob", "corr-b");

    @Test
    void isolatesAlternatingTasksOnAReusedPlatformThreadAndFailsClosedWithoutCapture()
            throws Exception {
        try (var pool = Executors.newFixedThreadPool(1)) {
            var a = ExecutionContextHolder.call(
                    TENANT_A,
                    () -> pool.submit(ExecutionContextSnapshot.capture().wrap(ExecutionContextHolder::require)));
            var b = ExecutionContextHolder.call(
                    TENANT_B,
                    () -> pool.submit(ExecutionContextSnapshot.capture().wrap(ExecutionContextHolder::require)));

            assertThat(a.get()).isEqualTo(TENANT_A);
            assertThat(b.get()).isEqualTo(TENANT_B);
            assertThatThrownBy(() -> pool.submit(ExecutionContextHolder::require).get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(MissingExecutionContextException.class);
            assertThat(pool.submit(ExecutionContextHolder::current).get()).isEmpty();
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void restoresAfterNormalExceptionalAndRejectedTasks() throws Exception {
        try (var pool = Executors.newFixedThreadPool(1)) {
            var failed = ExecutionContextHolder.call(TENANT_A, () -> pool.submit(
                    ExecutionContextSnapshot.capture().wrap((Runnable) () -> {
                        assertThat(ExecutionContextHolder.require()).isEqualTo(TENANT_A);
                        throw new IllegalStateException("boom");
                    })));
            assertThatThrownBy(failed::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(pool.submit(ExecutionContextHolder::current).get()).isEmpty();
        }

        try (var rejected = Executors.newSingleThreadExecutor()) {
            rejected.shutdown();
            ExecutionContextHolder.run(TENANT_A, () -> {
                Runnable captured = ExecutionContextSnapshot.capture().wrap(() -> {});
                assertThatThrownBy(() -> rejected.execute(captured))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(ExecutionContextHolder.require()).isEqualTo(TENANT_A);
            });
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void keepsTheFullContextUntilACancelledDelegateActuallyExits() throws Exception {
        var started = new CountDownLatch(1);
        var sampleAfterTimeout = new CountDownLatch(1);
        var sampledAfterTimeout = new CountDownLatch(1);
        var waitForCancellation = new CountDownLatch(1);
        var sampledAfterCancellation = new CountDownLatch(1);
        var releaseDelegate = new CountDownLatch(1);
        var delegateExited = new CountDownLatch(1);
        var contextAfterTimeout = new AtomicReference<ExecutionContext>();
        var contextAfterCancellation = new AtomicReference<ExecutionContext>();
        var delegateFailure = new AtomicReference<Throwable>();

        try (var pool = Executors.newFixedThreadPool(1)) {
            var waiting = ExecutionContextHolder.call(TENANT_B, () -> pool.submit(
                    ExecutionContextSnapshot.capture().wrap((Runnable) () -> {
                        started.countDown();
                        try {
                            try {
                                assertThat(sampleAfterTimeout.await(2, TimeUnit.SECONDS)).isTrue();
                                contextAfterTimeout.set(ExecutionContextHolder.require());
                                sampledAfterTimeout.countDown();
                                try {
                                    boolean releasedWithoutCancellation =
                                            waitForCancellation.await(2, TimeUnit.SECONDS);
                                    throw new AssertionError(releasedWithoutCancellation
                                            ? "delegate cancellation checkpoint was released normally"
                                            : "delegate did not receive cancellation within two seconds");
                                } catch (InterruptedException cancellation) {
                                    contextAfterCancellation.set(ExecutionContextHolder.require());
                                    sampledAfterCancellation.countDown();
                                    try {
                                        assertThat(releaseDelegate.await(2, TimeUnit.SECONDS)).isTrue();
                                    } catch (InterruptedException unexpected) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(
                                                "delegate interrupted after cancellation observation", unexpected);
                                    }
                                }
                            } catch (InterruptedException unexpected) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(
                                        "delegate interrupted before cancellation checkpoint", unexpected);
                            }
                        } catch (RuntimeException | Error failure) {
                            delegateFailure.set(failure);
                            throw failure;
                        } finally {
                            delegateExited.countDown();
                        }
                    })));

            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> waiting.get(1, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                sampleAfterTimeout.countDown();
                assertThat(sampledAfterTimeout.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(contextAfterTimeout.get()).isEqualTo(TENANT_B);
                assertThat(delegateExited.getCount()).isEqualTo(1);

                assertThat(waiting.cancel(true)).isTrue();
                assertThat(waiting.isCancelled()).isTrue();
                assertThat(sampledAfterCancellation.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(contextAfterCancellation.get()).isEqualTo(TENANT_B);
                assertThat(delegateExited.getCount()).isEqualTo(1);

                releaseDelegate.countDown();
                assertThat(delegateExited.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(delegateFailure.get()).isNull();
                assertThat(pool.submit(ExecutionContextHolder::current).get(2, TimeUnit.SECONDS))
                        .isEmpty();
            } finally {
                sampleAfterTimeout.countDown();
                waiting.cancel(true);
                waitForCancellation.countDown();
                releaseDelegate.countDown();
            }
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void isolatesAndRestoresCompleteContextsForTwoRequestsInTheSameTenant() throws Exception {
        assertThat(TENANT_A_SECOND_REQUEST.tenantId()).isEqualTo(TENANT_A.tenantId());
        assertThat(TENANT_A_SECOND_REQUEST.actor()).isNotEqualTo(TENANT_A.actor());
        assertThat(TENANT_A_SECOND_REQUEST.initiator()).isNotEqualTo(TENANT_A.initiator());
        assertThat(TENANT_A_SECOND_REQUEST.correlationId()).isNotEqualTo(TENANT_A.correlationId());

        ExecutionContextSnapshot firstRequest =
                ExecutionContextHolder.call(TENANT_A, ExecutionContextSnapshot::capture);
        ExecutionContextSnapshot secondRequest = ExecutionContextHolder.call(
                TENANT_A_SECOND_REQUEST, ExecutionContextSnapshot::capture);

        ExecutionContextHolder.run(TENANT_A, () -> {
            assertThat(ExecutionContextHolder.require()).isEqualTo(TENANT_A);
            try (var second = secondRequest.openScope()) {
                assertThat(ExecutionContextHolder.require()).isEqualTo(TENANT_A_SECOND_REQUEST);
                try (var first = firstRequest.openScope()) {
                    assertThat(ExecutionContextHolder.require()).isEqualTo(TENANT_A);
                }
                assertThat(ExecutionContextHolder.require()).isEqualTo(TENANT_A_SECOND_REQUEST);
            }
            assertThat(ExecutionContextHolder.require()).isEqualTo(TENANT_A);
        });
        assertThat(ExecutionContextHolder.current()).isEmpty();

        try (var pool = Executors.newFixedThreadPool(1)) {
            assertThat(pool.submit(firstRequest.wrap(ExecutionContextHolder::require)).get())
                    .isEqualTo(TENANT_A);
            assertThat(pool.submit(secondRequest.wrap(ExecutionContextHolder::require)).get())
                    .isEqualTo(TENANT_A_SECOND_REQUEST);
            assertThat(pool.submit(ExecutionContextHolder::current).get()).isEmpty();
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void propagatesExplicitlyToVirtualThreadsWithoutImplicitInheritance() throws Exception {
        try (var virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            var snapshots = List.of(TENANT_A, TENANT_B).stream()
                    .map(context -> ExecutionContextHolder.call(context, () -> virtualThreads.submit(
                            ExecutionContextSnapshot.capture().wrap(() -> new ThreadObservation(
                                    Thread.currentThread().isVirtual(), ExecutionContextHolder.require())))))
                    .toList();

            assertThat(snapshots.get(0).get()).isEqualTo(new ThreadObservation(true, TENANT_A));
            assertThat(snapshots.get(1).get()).isEqualTo(new ThreadObservation(true, TENANT_B));
            assertThatThrownBy(() -> virtualThreads.submit(ExecutionContextHolder::require).get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(MissingExecutionContextException.class);
        }
    }

    @Test
    void callableWrapPreservesTheOriginalCheckedException() {
        Exception failure = new Exception("checked");
        Callable<String> wrapped = ExecutionContextHolder.call(
                TENANT_A, () -> ExecutionContextSnapshot.capture().wrap(() -> {
                    throw failure;
                }));

        assertThatThrownBy(wrapped::call).isSameAs(failure);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void rejectsNullFactoryInputAndKeepsStrictCaptureFailClosed() {
        assertThatThrownBy(() -> ExecutionContextSnapshot.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
        assertThatThrownBy(ExecutionContextSnapshot::capture)
                .isInstanceOf(MissingExecutionContextException.class);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void explicitAbsentSnapshotDoesNotReadItsConstructionThreadIdentity() {
        ExecutionContextSnapshot absent =
                ExecutionContextHolder.call(TENANT_A, ExecutionContextSnapshot::absent);

        ExecutionContextHolder.run(TENANT_B, () -> {
            try (var ignored = absent.openScope()) {
                assertThat(ExecutionContextHolder.current()).isEmpty();
                assertThatThrownBy(ExecutionContextHolder::require)
                        .isInstanceOf(MissingExecutionContextException.class);
            }
            assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_B);
        });
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void restoresEveryPresentAndAbsentSnapshotCombination() {
        ExecutionContextSnapshot capturedAbsent = ExecutionContextSnapshot.captureAllowingAbsent();
        ExecutionContextSnapshot capturedPresent =
                ExecutionContextHolder.call(TENANT_A, ExecutionContextSnapshot::captureAllowingAbsent);

        try (var ignored = capturedAbsent.openScope()) {
            assertThat(ExecutionContextHolder.current()).isEmpty();
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();

        try (var ignored = capturedPresent.openScope()) {
            assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_A);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();

        ExecutionContextHolder.run(TENANT_B, () -> {
            try (var ignored = capturedAbsent.openScope()) {
                assertThat(ExecutionContextHolder.current()).isEmpty();
            }
            assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_B);

            try (var ignored = capturedPresent.openScope()) {
                assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_A);
            }
            assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_B);
        });
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void absentSnapshotScopesAlsoRequireReverseClosure() {
        ExecutionContextHolder.run(TENANT_B, () -> {
            ExecutionContextHolder.Scope outer = ExecutionContextSnapshot.absent().openScope();
            ExecutionContextHolder.Scope inner = ExecutionContextSnapshot.absent().openScope();
            try {
                assertThatThrownBy(outer::close)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("reverse order");
                assertThat(ExecutionContextHolder.current()).isEmpty();
            } finally {
                inner.close();
                outer.close();
            }
            assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_B);
        });
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void preservesNormalRuntimeAndErrorOutcomesWhileRestoring() throws Exception {
        ExecutionContextSnapshot snapshot = ExecutionContextSnapshot.of(TENANT_A);
        RuntimeException runtimeFailure = new IllegalStateException("runtime");
        Error errorFailure = new AssertionError("error");

        ExecutionContextHolder.run(TENANT_B, () -> {
            Runnable normal = snapshot.wrap((Runnable) () ->
                    assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_A));
            normal.run();
            assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_B);

            assertThatThrownBy(snapshot.wrap((Runnable) () -> {
                        throw runtimeFailure;
                    })::run)
                    .isSameAs(runtimeFailure);
            assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_B);

            assertThatThrownBy(snapshot.wrap((Runnable) () -> {
                        throw errorFailure;
                    })::run)
                    .isSameAs(errorFailure);
            assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_B);
        });

        Callable<String> returning = snapshot.wrap(() -> "result");
        assertThat(returning.call()).isEqualTo("result");
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void capturedSnapshotRemainsUsableAfterItsParentScopeExits() throws Exception {
        ExecutionContextSnapshot snapshot =
                ExecutionContextHolder.call(TENANT_A, ExecutionContextSnapshot::capture);

        assertThat(ExecutionContextHolder.current()).isEmpty();
        assertThat(snapshot.wrap(ExecutionContextHolder::require).call()).isSameAs(TENANT_A);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void legacyHolderAndSnapshotEntryPointsRemainUsable() throws Exception {
        assertThat(ExecutionContextHolder.call(TENANT_A, () -> "holder-result"))
                .isEqualTo("holder-result");
        ExecutionContextHolder.run(TENANT_A, () -> assertThat(ExecutionContextHolder.require())
                .isSameAs(TENANT_A));

        ExecutionContextSnapshot snapshot = ExecutionContextSnapshot.of(TENANT_A);
        snapshot.wrap((Runnable) () -> assertThat(ExecutionContextHolder.require()).isSameAs(TENANT_A))
                .run();
        assertThat(snapshot.wrap(() -> "snapshot-result").call()).isEqualTo("snapshot-result");
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private static ExecutionContext context(String tenant, String subject, String correlation) {
        return ExecutionContext.initiatedBy(
                new TenantId(tenant), new Actor(ActorType.USER, subject), correlation);
    }

    private record ThreadObservation(boolean virtual, ExecutionContext context) {}
}
