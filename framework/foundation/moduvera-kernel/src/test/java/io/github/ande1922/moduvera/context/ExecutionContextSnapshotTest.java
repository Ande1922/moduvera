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
import org.junit.jupiter.api.Test;

class ExecutionContextSnapshotTest {

    private static final ExecutionContext TENANT_A = context("tenant-a", "alice", "corr-a");
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
    void restoresAfterNormalExceptionalTimedOutCancelledAndRejectedTasks() throws Exception {
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

            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var waiting = ExecutionContextHolder.call(TENANT_B, () -> pool.submit(
                    ExecutionContextSnapshot.capture().wrap((Runnable) () -> {
                        started.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    })));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiting.get(1, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            waiting.cancel(true);
            release.countDown();
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
