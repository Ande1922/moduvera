package io.github.ande1922.moduvera.logging;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot;
import io.opentelemetry.context.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * Fixed snapshot of the business and OpenTelemetry state for one short callback scope.
 *
 * <p>The snapshot captures the complete OpenTelemetry {@link Context}, not only its current Span.
 * Opening it also projects the trusted business and trace fields into MDC for the duration of the
 * callback. Closing restores the exact prior business context, OTel scope, and only the MDC keys
 * owned by this component.
 */
public final class LoggingContextSnapshot {

    private final ExecutionContextSnapshot executionContext;
    private final Context telemetryContext;

    private LoggingContextSnapshot(
            ExecutionContextSnapshot executionContext, Context telemetryContext) {
        this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
        this.telemetryContext = Objects.requireNonNull(telemetryContext, "telemetryContext");
    }

    /** Captures the current trusted business context and complete OTel Context. */
    public static LoggingContextSnapshot capture() {
        return new LoggingContextSnapshot(ExecutionContextSnapshot.capture(), Context.current());
    }

    /** Captures the complete current state, including an explicitly absent business context. */
    public static LoggingContextSnapshot captureAllowingAbsent() {
        return new LoggingContextSnapshot(
                ExecutionContextSnapshot.captureAllowingAbsent(), Context.current());
    }

    /** Creates a snapshot that masks both business and telemetry state while installed. */
    public static LoggingContextSnapshot absent() {
        return new LoggingContextSnapshot(ExecutionContextSnapshot.absent(), Context.root());
    }

    /** Installs the captured state until the returned thread-owned scope closes. */
    public Scope openScope() {
        ExecutionContextHolder.Scope executionScope = executionContext.openScope();
        io.opentelemetry.context.Scope telemetryScope;
        try {
            telemetryScope = telemetryContext.makeCurrent();
        } catch (RuntimeException failure) {
            executionScope.close();
            throw failure;
        }
        MdcProjection mdcProjection;
        try {
            mdcProjection = MdcProjection.open();
        } catch (RuntimeException failure) {
            telemetryScope.close();
            executionScope.close();
            throw failure;
        }
        return new Scope(executionScope, telemetryScope, mdcProjection);
    }

    /** One thread-owned installation of a {@link LoggingContextSnapshot}. */
    public static final class Scope implements AutoCloseable {

        private final Thread owner = Thread.currentThread();
        private final ExecutionContextHolder.Scope executionScope;
        private final io.opentelemetry.context.Scope telemetryScope;
        private final MdcProjection mdcProjection;
        private boolean closed;

        private Scope(
                ExecutionContextHolder.Scope executionScope,
                io.opentelemetry.context.Scope telemetryScope,
                MdcProjection mdcProjection) {
            this.executionScope = executionScope;
            this.telemetryScope = telemetryScope;
            this.mdcProjection = mdcProjection;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("logging context scope must close on its owning thread");
            }
            closed = true;
            try {
                mdcProjection.close();
            } finally {
                try {
                    telemetryScope.close();
                } finally {
                    executionScope.close();
                }
            }
        }
    }

    private static final class MdcProjection implements AutoCloseable {

        private final Map<String, String> previous;

        private MdcProjection(Map<String, String> previous) {
            this.previous = previous;
        }

        static MdcProjection open() {
            Map<String, String> current = MDC.getCopyOfContextMap();
            Map<String, String> previous = current == null ? Map.of() : new LinkedHashMap<>(current);
            Map<String, String> trusted = TrustedLogContext.currentFields();
            for (String fieldName : TrustedLogContext.FIELD_NAMES) {
                String value = trusted.get(fieldName);
                if (value == null) {
                    MDC.remove(fieldName);
                } else {
                    MDC.put(fieldName, value);
                }
            }
            return new MdcProjection(previous);
        }

        @Override
        public void close() {
            for (String fieldName : TrustedLogContext.FIELD_NAMES) {
                String value = previous.get(fieldName);
                if (value == null) {
                    MDC.remove(fieldName);
                } else {
                    MDC.put(fieldName, value);
                }
            }
        }
    }
}
