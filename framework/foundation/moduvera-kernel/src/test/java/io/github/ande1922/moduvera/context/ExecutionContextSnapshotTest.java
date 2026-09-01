package io.github.ande1922.moduvera.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

    private static ExecutionContext context(String tenant, String subject, String correlation) {
        return ExecutionContext.initiatedBy(
                new TenantId(tenant), new Actor(ActorType.USER, subject), correlation);
    }

    private record ThreadObservation(boolean virtual, ExecutionContext context) {}
}
