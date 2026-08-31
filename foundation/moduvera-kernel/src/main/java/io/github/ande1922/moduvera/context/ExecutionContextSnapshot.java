package io.github.ande1922.moduvera.context;

import java.util.Objects;
import java.util.concurrent.Callable;

/** Explicit, one-context snapshot for tasks that cross a thread boundary. */
public final class ExecutionContextSnapshot {

    private final ExecutionContext captured;

    private ExecutionContextSnapshot(ExecutionContext captured) {
        this.captured = captured;
    }

    public static ExecutionContextSnapshot capture() {
        return new ExecutionContextSnapshot(ExecutionContextHolder.require());
    }

    public Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> ExecutionContextHolder.run(captured, task);
    }

    public <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return () -> ExecutionContextHolder.call(captured, () -> {
            try {
                return task.call();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new ContextTaskException(failure);
            }
        });
    }

    private static final class ContextTaskException extends RuntimeException {

        private ContextTaskException(Exception cause) {
            super(cause);
        }
    }
}
