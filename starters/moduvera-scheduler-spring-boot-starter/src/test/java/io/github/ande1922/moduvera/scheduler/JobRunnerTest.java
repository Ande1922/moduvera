package io.github.ande1922.moduvera.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.lock.LockTemplate;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static ExecutionContext context() {
        Actor actor = new Actor(ActorType.SYSTEM, "outbox-job");
        return ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-job");
    }
}
