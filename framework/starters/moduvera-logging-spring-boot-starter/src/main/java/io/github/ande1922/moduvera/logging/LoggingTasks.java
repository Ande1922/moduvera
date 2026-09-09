package io.github.ande1922.moduvera.logging;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit new in-process task boundaries, bound before submission to a JDK executor. */
public final class LoggingTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger("task.execute");

    private LoggingTasks() {}

    /**
     * Captures the submitting execution and wraps the actual task body, not a Future or a
     * framework dispatch wrapper. Each invocation is one new execution with its own Span and
     * canonical result. Never cache the bound body across requests.
     *
     * <p>Pass the result to the executor's execute/submit method. The executor retains rejection,
     * queue, Future, cancellation and shutdown ownership. A cancelled queued Future does not invoke
     * this body. Cancellation notification never closes a running body's thread-owned scope.
     * Failure is rethrown unchanged; its caller/Future consumer owns recovery or final ERROR.
     */
    public static Runnable bindRunnable(String taskName, Runnable body) {
        Objects.requireNonNull(body, "body");
        LoggingContextSnapshot parent = capture(taskName);
        return () -> {
            try (LoggingContextSnapshot.Scope ignored = parent.openScope();
                    TaskExecution execution = new TaskExecution(taskName)) {
                body.run();
                execution.succeeded = true;
            }
        };
    }

    /** Same boundary as {@link #bindRunnable}, preserving the result and checked exceptions. */
    public static <T> Callable<T> bindCallable(String taskName, Callable<T> body) {
        Objects.requireNonNull(body, "body");
        LoggingContextSnapshot parent = capture(taskName);
        return () -> {
            try (LoggingContextSnapshot.Scope ignored = parent.openScope();
                    TaskExecution execution = new TaskExecution(taskName)) {
                T result = body.call();
                execution.succeeded = true;
                return result;
            }
        };
    }

    private static LoggingContextSnapshot capture(String taskName) {
        Objects.requireNonNull(taskName, "taskName");
        if (!taskName.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("taskName must be a stable bounded identifier");
        }
        return LoggingContextSnapshot.captureAllowingAbsent();
    }

    private static final class TaskExecution implements AutoCloseable {

        private final String taskName;
        private final long started;
        private final Span span;
        private final io.opentelemetry.context.Scope spanScope;
        private final LoggingContextSnapshot.Scope loggingScope;
        private boolean succeeded;

        private TaskExecution(String taskName) {
            this.taskName = taskName;
            span = GlobalOpenTelemetry.getTracer("io.github.ande1922.moduvera.tasks")
                    .spanBuilder(taskName)
                    .startSpan();
            spanScope = span.makeCurrent();
            loggingScope = LoggingContextSnapshot.captureAllowingAbsent().openScope();
            started = System.nanoTime();
        }

        @Override
        public void close() {
            try {
                if (!succeeded) {
                    span.setStatus(StatusCode.ERROR);
                }
                LOGGER.atInfo()
                        .addKeyValue("task_name", taskName)
                        .addKeyValue("event.outcome", succeeded ? "success" : "failure")
                        .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0d)
                        .log("任务执行结束");
            } finally {
                try {
                    loggingScope.close();
                } finally {
                    try {
                        spanScope.close();
                    } finally {
                        span.end();
                    }
                }
            }
        }
    }
}
