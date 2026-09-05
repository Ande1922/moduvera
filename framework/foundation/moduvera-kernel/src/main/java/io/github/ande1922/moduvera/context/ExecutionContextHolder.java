package io.github.ande1922.moduvera.context;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Thread-local access to the trusted execution context for the current synchronous call. */
public final class ExecutionContextHolder {

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private ExecutionContextHolder() {}

    /** Returns the context installed for the current thread, if one is present. */
    public static Optional<ExecutionContext> current() {
        Binding binding = CURRENT.get();
        return binding == null ? Optional.empty() : binding.context();
    }

    /** Returns the current context or fails closed when no context is installed. */
    public static ExecutionContext require() {
        return current().orElseThrow(MissingExecutionContextException::new);
    }

    /**
     * Installs a trusted, non-null context until the returned scope is closed on this thread.
     * Scopes must close in reverse-open order.
     */
    public static Scope open(ExecutionContext context) {
        return install(Optional.of(Objects.requireNonNull(context, "context")));
    }

    /** Runs an action with the trusted context installed and restores the prior state afterward. */
    public static <T> T call(ExecutionContext context, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        try (Scope ignored = open(context)) {
            return action.get();
        }
    }

    /** Runs an action with the trusted context installed and restores the prior state afterward. */
    public static void run(ExecutionContext context, Runnable action) {
        Objects.requireNonNull(action, "action");
        try (Scope ignored = open(context)) {
            action.run();
        }
    }

    static Scope openSnapshot(Optional<ExecutionContext> captured) {
        return install(Objects.requireNonNull(captured, "captured"));
    }

    private static Scope install(Optional<ExecutionContext> context) {
        Binding previous = CURRENT.get();
        Binding installed = new Binding(context);
        CURRENT.set(installed);
        return new Scope(Thread.currentThread(), installed, previous);
    }

    /** A single thread-owned holder binding that restores the exact preceding binding on close. */
    public static final class Scope implements AutoCloseable {

        private final Thread owner;
        private final Binding installed;
        private final Binding previous;
        private volatile boolean closed;

        private Scope(Thread owner, Binding installed, Binding previous) {
            this.owner = owner;
            this.installed = installed;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("execution context scope must close on its owning thread");
            }
            if (CURRENT.get() != installed) {
                throw new IllegalStateException("execution context scopes must close in reverse order");
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private record Binding(Optional<ExecutionContext> context) {}
}
