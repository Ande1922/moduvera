package io.github.ande1922.moduvera.context;

import java.util.Optional;
import java.util.function.Supplier;

public final class ExecutionContextHolder {

    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();

    private ExecutionContextHolder() {}

    public static Optional<ExecutionContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static ExecutionContext require() {
        return current().orElseThrow(MissingExecutionContextException::new);
    }

    public static Scope open(ExecutionContext context) {
        ExecutionContext previous = CURRENT.get();
        CURRENT.set(context);
        return new Scope(Thread.currentThread(), context, previous);
    }

    public static <T> T call(ExecutionContext context, Supplier<T> action) {
        try (Scope ignored = open(context)) {
            return action.get();
        }
    }

    public static void run(ExecutionContext context, Runnable action) {
        try (Scope ignored = open(context)) {
            action.run();
        }
    }

    public static final class Scope implements AutoCloseable {

        private final Thread owner;
        private final ExecutionContext installed;
        private final ExecutionContext previous;
        private boolean closed;

        private Scope(Thread owner, ExecutionContext installed, ExecutionContext previous) {
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
}
