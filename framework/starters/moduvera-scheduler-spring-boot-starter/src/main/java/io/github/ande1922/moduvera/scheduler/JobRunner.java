package io.github.ande1922.moduvera.scheduler;

import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.lock.LockKey;
import io.github.ande1922.moduvera.lock.LockNotAcquiredException;
import io.github.ande1922.moduvera.lock.LockRetryPolicy;
import io.github.ande1922.moduvera.lock.LockTemplate;

public final class JobRunner {

    private final LockTemplate locks;

    public JobRunner(LockTemplate locks) {
        this.locks = locks;
    }

    public JobOutcome run(JobDefinition definition, ExecutionContext context, Runnable applicationUseCase) {
        LockKey key = definition.scope() == JobDefinition.Scope.TENANT
                ? LockKey.tenant(context.requireTenantId(), "job", definition.name())
                : LockKey.global("job", definition.name());
        try {
            return locks.execute(
                    key,
                    LockRetryPolicy.noRetry(definition.lockTimeout()),
                    () -> {
                        ExecutionContextHolder.run(context, applicationUseCase);
                        return JobOutcome.COMPLETED;
                    });
        } catch (LockNotAcquiredException overlap) {
            return JobOutcome.SKIPPED_OVERLAP;
        }
    }
}
