package io.github.ande1922.moduvera.context;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Fixed snapshot of one logical execution's context state for an explicit propagation boundary.
 * A snapshot remains valid after the scope in which it was created exits. Do not cache a captured
 * request snapshot in singleton or cross-request state.
 */
public final class ExecutionContextSnapshot {

    private static final ExecutionContextSnapshot ABSENT = new ExecutionContextSnapshot(Optional.empty());

    private final Optional<ExecutionContext> captured;

    private ExecutionContextSnapshot(Optional<ExecutionContext> captured) {
        this.captured = captured;
    }

    /** Captures the current context and fails immediately when it is absent. */
    public static ExecutionContextSnapshot capture() {
        return of(ExecutionContextHolder.require());
    }

    /** Captures either the current context or an explicit absent state. */
    public static ExecutionContextSnapshot captureAllowingAbsent() {
        return new ExecutionContextSnapshot(ExecutionContextHolder.current());
    }

    /** Creates a fixed snapshot from a trusted, non-null context. */
    public static ExecutionContextSnapshot of(ExecutionContext context) {
        return new ExecutionContextSnapshot(Optional.of(Objects.requireNonNull(context, "context")));
    }

    /**
     * Creates an explicit absent snapshot without consulting the calling thread's holder state.
     * Opening it temporarily hides any context already installed on the execution thread.
     */
    public static ExecutionContextSnapshot absent() {
        return ABSENT;
    }

    /** Installs this captured state until the returned scope closes on the execution thread. */
    public ExecutionContextHolder.Scope openScope() {
        return ExecutionContextHolder.openSnapshot(captured);
    }

    /** Wraps a Runnable while preserving its result and exception behavior. */
    public Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try (ExecutionContextHolder.Scope ignored = openScope()) {
                task.run();
            }
        };
    }

    /** Wraps a Callable while preserving its return value and original checked exception. */
    public <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try (ExecutionContextHolder.Scope ignored = openScope()) {
                return task.call();
            }
        };
    }
}
