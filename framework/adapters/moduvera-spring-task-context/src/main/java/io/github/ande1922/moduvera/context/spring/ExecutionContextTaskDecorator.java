package io.github.ande1922.moduvera.context.spring;

import io.github.ande1922.moduvera.context.ExecutionContextSnapshot;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the submitting thread's present or absent Execution Context through one explicitly
 * selected Spring task executor.
 *
 * <p>This decorator is reusable because it captures a new snapshot whenever Spring decorates an
 * actual submitted task. Constructing it does not capture the current thread's identity. The
 * bound task installs that snapshot only while its delegate runs and restores the worker's prior
 * state on actual exit.
 */
public final class ExecutionContextTaskDecorator implements TaskDecorator {

    @Override
    public ExecutionContextSnapshot.BoundRunnable decorate(Runnable runnable) {
        return ExecutionContextSnapshot.captureAllowingAbsent().bindRunnable(runnable);
    }
}
