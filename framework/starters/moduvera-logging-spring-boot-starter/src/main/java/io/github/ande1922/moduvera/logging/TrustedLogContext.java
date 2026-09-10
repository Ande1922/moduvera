package io.github.ande1922.moduvera.logging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TrustedLogContext {

    static final List<String> FIELD_NAMES = List.of(
            "trace_id",
            "span_id",
            "correlation_id",
            "tenant_id",
            "actor_type",
            "actor_id",
            "initiator_type",
            "initiator_id",
            "user_id");

    private TrustedLogContext() {}

    static Map<String, String> currentFields() {
        Map<String, String> diagnostic = DiagnosticLogSnapshot.currentFields();
        if (diagnostic != null) {
            return diagnostic;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            fields.put("trace_id", spanContext.getTraceId());
            fields.put("span_id", spanContext.getSpanId());
        }
        ExecutionContextHolder.current().ifPresent(context -> addExecutionContext(fields, context));
        return fields;
    }

    static void addExecutionContext(Map<String, String> fields, ExecutionContext context) {
        fields.put("correlation_id", context.correlationId());
        if (context.scope() instanceof ExecutionScope.Tenant tenant) {
            fields.put("tenant_id", tenant.tenantId().value());
        }
        addIdentity(fields, context.actor(), context.initiator());
    }

    static void addIdentity(Map<String, String> fields, Actor actor, Initiator initiator) {
        fields.put("actor_type", actor.type().name());
        fields.put("actor_id", actor.subjectId());
        fields.put("initiator_type", initiator.type().name());
        fields.put("initiator_id", initiator.subjectId());
        if (actor.type() == ActorType.USER) {
            fields.put("user_id", actor.subjectId());
        }
    }
}
