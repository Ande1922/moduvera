package io.github.ande1922.moduvera.context;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /**
     * Binds a Runnable to this logical execution.
     *
     * <p>The returned object may be invoked repeatedly within that execution, but must not be
     * cached or shared across requests, even when they have the same tenant.
     */
    public BoundRunnable bindRunnable(Runnable task) {
        return new BoundRunnable(this, task);
    }

    /** Binds a Callable to this logical execution. */
    public <T> BoundCallable<T> bindCallable(Callable<T> task) {
        return new BoundCallable<>(this, task);
    }

    /** Binds a Supplier to this logical execution. */
    public <T> BoundSupplier<T> bindSupplier(Supplier<T> supplier) {
        return new BoundSupplier<>(this, supplier);
    }

    /** Binds a Function to this logical execution. */
    public <T, R> BoundFunction<T, R> bindFunction(Function<T, R> function) {
        return new BoundFunction<>(this, function);
    }

    /** Binds a Consumer to this logical execution. */
    public <T> BoundConsumer<T> bindConsumer(Consumer<T> consumer) {
        return new BoundConsumer<>(this, consumer);
    }

    /** Binds a BiFunction to this logical execution. */
    public <T, U, R> BoundBiFunction<T, U, R> bindBiFunction(BiFunction<T, U, R> function) {
        return new BoundBiFunction<>(this, function);
    }

    /** Binds a BiConsumer to this logical execution. */
    public <T, U> BoundBiConsumer<T, U> bindBiConsumer(BiConsumer<T, U> consumer) {
        return new BoundBiConsumer<>(this, consumer);
    }

    /** Wraps a Runnable through the same named binding mechanism. */
    public Runnable wrap(Runnable task) {
        return bindRunnable(task);
    }

    /** Wraps a Callable through the same named binding mechanism. */
    public <T> Callable<T> wrap(Callable<T> task) {
        return bindCallable(task);
    }

    /** A Runnable bound to one fixed request snapshot. Never cache it across requests. */
    public static final class BoundRunnable implements Runnable {

        private final ExecutionContextSnapshot snapshot;
        private final Runnable task;

        private BoundRunnable(ExecutionContextSnapshot snapshot, Runnable task) {
            this.snapshot = snapshot;
            this.task = Objects.requireNonNull(task, "task");
        }

        @Override
        public void run() {
            try (ExecutionContextHolder.Scope ignored = snapshot.openScope()) {
                task.run();
            }
        }
    }

    /** A Callable bound to one fixed request snapshot. Never cache it across requests. */
    public static final class BoundCallable<T> implements Callable<T> {

        private final ExecutionContextSnapshot snapshot;
        private final Callable<T> task;

        private BoundCallable(ExecutionContextSnapshot snapshot, Callable<T> task) {
            this.snapshot = snapshot;
            this.task = Objects.requireNonNull(task, "task");
        }

        @Override
        public T call() throws Exception {
            try (ExecutionContextHolder.Scope ignored = snapshot.openScope()) {
                return task.call();
            }
        }
    }

    /** A Supplier bound to one fixed request snapshot. Never cache it across requests. */
    public static final class BoundSupplier<T> implements Supplier<T> {

        private final ExecutionContextSnapshot snapshot;
        private final Supplier<T> supplier;

        private BoundSupplier(ExecutionContextSnapshot snapshot, Supplier<T> supplier) {
            this.snapshot = snapshot;
            this.supplier = Objects.requireNonNull(supplier, "supplier");
        }

        @Override
        public T get() {
            try (ExecutionContextHolder.Scope ignored = snapshot.openScope()) {
                return supplier.get();
            }
        }
    }

    /** A Function bound to one fixed request snapshot. Never cache it across requests. */
    public static final class BoundFunction<T, R> implements Function<T, R> {

        private final ExecutionContextSnapshot snapshot;
        private final Function<T, R> function;

        private BoundFunction(ExecutionContextSnapshot snapshot, Function<T, R> function) {
            this.snapshot = snapshot;
            this.function = Objects.requireNonNull(function, "function");
        }

        @Override
        public R apply(T value) {
            try (ExecutionContextHolder.Scope ignored = snapshot.openScope()) {
                return function.apply(value);
            }
        }
    }

    /** A Consumer bound to one fixed request snapshot. Never cache it across requests. */
    public static final class BoundConsumer<T> implements Consumer<T> {

        private final ExecutionContextSnapshot snapshot;
        private final Consumer<T> consumer;

        private BoundConsumer(ExecutionContextSnapshot snapshot, Consumer<T> consumer) {
            this.snapshot = snapshot;
            this.consumer = Objects.requireNonNull(consumer, "consumer");
        }

        @Override
        public void accept(T value) {
            try (ExecutionContextHolder.Scope ignored = snapshot.openScope()) {
                consumer.accept(value);
            }
        }
    }

    /** A BiFunction bound to one fixed request snapshot. Never cache it across requests. */
    public static final class BoundBiFunction<T, U, R> implements BiFunction<T, U, R> {

        private final ExecutionContextSnapshot snapshot;
        private final BiFunction<T, U, R> function;

        private BoundBiFunction(
                ExecutionContextSnapshot snapshot, BiFunction<T, U, R> function) {
            this.snapshot = snapshot;
            this.function = Objects.requireNonNull(function, "function");
        }

        @Override
        public R apply(T first, U second) {
            try (ExecutionContextHolder.Scope ignored = snapshot.openScope()) {
                return function.apply(first, second);
            }
        }
    }

    /** A BiConsumer bound to one fixed request snapshot. Never cache it across requests. */
    public static final class BoundBiConsumer<T, U> implements BiConsumer<T, U> {

        private final ExecutionContextSnapshot snapshot;
        private final BiConsumer<T, U> consumer;

        private BoundBiConsumer(
                ExecutionContextSnapshot snapshot, BiConsumer<T, U> consumer) {
            this.snapshot = snapshot;
            this.consumer = Objects.requireNonNull(consumer, "consumer");
        }

        @Override
        public void accept(T first, U second) {
            try (ExecutionContextHolder.Scope ignored = snapshot.openScope()) {
                consumer.accept(first, second);
            }
        }
    }
}
