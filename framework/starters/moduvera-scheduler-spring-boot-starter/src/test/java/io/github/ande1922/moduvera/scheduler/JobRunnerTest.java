package io.github.ande1922.moduvera.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.lock.LockKey;
import io.github.ande1922.moduvera.lock.LockTemplate;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JobRunnerTest {

    @Test
    void establishesAndClearsSystemContextAroundTheApplicationUseCase() {
        var runner = new JobRunner(new LockTemplate((key, timeout) -> Optional.of(() -> {})));
        AtomicBoolean invoked = new AtomicBoolean();

        JobOutcome outcome = runner.run(
                new JobDefinition("outbox-publisher", JobDefinition.Scope.TENANT, Duration.ZERO),
                context(),
                () -> {
                    assertThat(ExecutionContextHolder.require().actor().type()).isEqualTo(ActorType.SYSTEM);
                    invoked.set(true);
                });

        assertThat(outcome).isEqualTo(JobOutcome.COMPLETED);
        assertThat(invoked).isTrue();
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void skipsOverlapWithoutRunningTheUseCaseOrRetrying() {
        AtomicBoolean invoked = new AtomicBoolean();
        var runner = new JobRunner(new LockTemplate((key, timeout) -> Optional.empty()));

        JobOutcome outcome = runner.run(
                new JobDefinition("outbox-publisher", JobDefinition.Scope.GLOBAL, Duration.ZERO),
                context(),
                () -> invoked.set(true));

        assertThat(outcome).isEqualTo(JobOutcome.SKIPPED_OVERLAP);
        assertThat(invoked).isFalse();
    }

    @Test
    void globalCompetitionKeepsTheExplicitTenantExecutionContext() {
        var acquiredKey = new AtomicReference<LockKey>();
        var runner = new JobRunner(new LockTemplate((key, timeout) -> {
            acquiredKey.set(key);
            return Optional.of(() -> {});
        }));
        ExecutionContext tenant = context();

        JobOutcome outcome = runner.run(
                new JobDefinition("outbox-publisher", JobDefinition.Scope.GLOBAL, Duration.ZERO),
                tenant,
                () -> assertThat(ExecutionContextHolder.require()).isSameAs(tenant));

        assertThat(outcome).isEqualTo(JobOutcome.COMPLETED);
        assertThat(acquiredKey.get()).isEqualTo(LockKey.global("job", "outbox-publisher"));
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void platformCanRunAGlobalJobButCannotSupplyATenantLockKey() {
        var acquireCount = new AtomicInteger();
        var runner = new JobRunner(new LockTemplate((key, timeout) -> {
            acquireCount.incrementAndGet();
            return Optional.of(() -> {});
        }));
        var platform = ExecutionContext.initiatedBy(
                ExecutionScope.platform(),
                new Actor(ActorType.SYSTEM, "platform-job"),
                "corr-platform-job");

        assertThat(runner.run(
                        new JobDefinition("global-maintenance", JobDefinition.Scope.GLOBAL, Duration.ZERO),
                        platform,
                        () -> assertThat(ExecutionContextHolder.require()).isSameAs(platform)))
                .isEqualTo(JobOutcome.COMPLETED);
        assertThat(acquireCount.get()).isOne();
        assertThatThrownBy(() -> runner.run(
                        new JobDefinition("tenant-maintenance", JobDefinition.Scope.TENANT, Duration.ZERO),
                        platform,
                        () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tenant execution scope is required at this boundary");
        assertThat(acquireCount.get()).isOne();
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private static ExecutionContext context() {
        Actor actor = new Actor(ActorType.SYSTEM, "outbox-job");
        return ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-job");
    }
}
