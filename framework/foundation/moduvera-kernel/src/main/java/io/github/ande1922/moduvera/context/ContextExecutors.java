package io.github.ande1922.moduvera.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit Execution Context propagation for selected JDK executors. */
public final class ContextExecutors {

    private ContextExecutors() {}

    /**
     * Decorates an Executor without capturing the construction thread's context.
     *
     * <p>The returned decorator is reusable. Each call to {@link Executor#execute(Runnable)}
     * captures the submitting thread's present or absent context and restores it only while the
     * submitted command actually runs.
     */
    public static Executor propagating(Executor delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return command -> delegate.execute(bind(command));
    }

    /**
     * Decorates an ExecutorService without changing its lifecycle contract.
     *
     * <p>Each submitted task captures the submitting thread's present or absent context. Lifecycle
     * methods and the delegate's Futures, rejection behavior, and queued-task representations
     * remain owned by and are returned directly from the delegate.
     */
    public static ExecutorService propagating(ExecutorService delegate) {
        return new PropagatingExecutorService(delegate);
    }

    private static Runnable bind(Runnable command) {
        return ExecutionContextSnapshot.captureAllowingAbsent().bindRunnable(command);
    }

    private static <T> Callable<T> bind(Callable<T> task) {
        return ExecutionContextSnapshot.captureAllowingAbsent().bindCallable(task);
    }

    private static <T> List<Callable<T>> bindAll(Collection<? extends Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        List<Callable<T>> bound = new ArrayList<>();
        for (Callable<T> task : tasks) {
            bound.add(bind(task));
        }
        return bound;
    }

    private static final class PropagatingExecutorService implements ExecutorService {

        private final ExecutorService delegate;

        private PropagatingExecutorService(ExecutorService delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(bind(command));
        }

        @Override
        public Future<?> submit(Runnable task) {
            return delegate.submit(bind(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return delegate.submit(bind(task), result);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(bind(task));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return delegate.invokeAll(bindAll(tasks));
        }

        @Override
        public <T> List<Future<T>> invokeAll(
                Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.invokeAll(bindAll(tasks), timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return delegate.invokeAny(bindAll(tasks));
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.invokeAny(bindAll(tasks), timeout, unit);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
