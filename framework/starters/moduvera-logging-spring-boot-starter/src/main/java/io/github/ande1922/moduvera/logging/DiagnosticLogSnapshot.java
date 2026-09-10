package io.github.ande1922.moduvera.logging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable diagnostic fields; opening this snapshot never installs authorization state. */
public final class DiagnosticLogSnapshot {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final Context telemetry;
    private final Map<String, String> fields;

    private DiagnosticLogSnapshot(Context telemetry, Map<String, String> fields) {
        this.telemetry = telemetry;
        this.fields = Map.copyOf(fields);
    }

    public static DiagnosticLogSnapshot capture(String correlationId) {
        DiagnosticLogSnapshot snapshot = captureWithoutIdentity(correlationId);
        return ExecutionContextHolder.current().map(snapshot::withIdentity).orElse(snapshot);
    }

    /** Captures an ingress before authentication, ignoring any enclosing business scope. */
    public static DiagnosticLogSnapshot captureWithoutIdentity(String correlationId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("correlation_id", Objects.requireNonNull(correlationId, "correlationId"));
        var span = Span.current().getSpanContext();
        if (span.isValid()) {
            fields.put("trace_id", span.getTraceId());
            fields.put("span_id", span.getSpanId());
        }
        return new DiagnosticLogSnapshot(Context.current(), fields);
    }

    public DiagnosticLogSnapshot withIdentity(ExecutionContext trusted) {
        Map<String, String> enriched = new LinkedHashMap<>();
        TrustedLogContext.addExecutionContext(enriched, trusted);
        return withFields(enriched);
    }

    /** Records authenticated identity even when tenant authorization subsequently rejects the request. */
    public DiagnosticLogSnapshot withIdentity(Actor actor, Initiator initiator, Optional<TenantId> tenant) {
        Map<String, String> enriched = new LinkedHashMap<>();
        TrustedLogContext.addIdentity(enriched, actor, initiator);
        tenant.ifPresent(value -> enriched.put("tenant_id", value.value()));
        return withFields(enriched);
    }

    private DiagnosticLogSnapshot withFields(Map<String, String> enriched) {
        enriched.put("correlation_id", fields.get("correlation_id"));
        if (fields.containsKey("trace_id")) {
            enriched.put("trace_id", fields.get("trace_id"));
            enriched.put("span_id", fields.get("span_id"));
        }
        return new DiagnosticLogSnapshot(telemetry, enriched);
    }

    public String traceId() {
        return fields.get("trace_id");
    }

    public Scope openScope() {
        Scope scope = new Scope(this, CURRENT.get(), telemetry.makeCurrent());
        CURRENT.set(scope);
        return scope;
    }

    static Map<String, String> currentFields() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.snapshot.fields;
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final DiagnosticLogSnapshot snapshot;
        private final Scope previous;
        private final io.opentelemetry.context.Scope telemetryScope;
        private boolean closed;

        private Scope(DiagnosticLogSnapshot snapshot, Scope previous, io.opentelemetry.context.Scope telemetryScope) {
            this.snapshot = snapshot;
            this.previous = previous;
            this.telemetryScope = telemetryScope;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner || CURRENT.get() != this) {
                throw new IllegalStateException("diagnostic scopes must close in order on their owning thread");
            }
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            telemetryScope.close();
            closed = true;
        }
    }
}
