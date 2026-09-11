package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxMessage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Short database preparation lifetime; no publication attempt or authorization scope is installed. */
final class OutboxPublicationPreparation implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("outbox.recovery");
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private Span span;
    private Scope scope;
    private ClaimedOutboxMessage repaired;
    private boolean committed;

    TraceContextCarrier replacement(ClaimedOutboxMessage current) {
        if (Span.fromContext(extract(current.publicationTraceParent(), current.publicationTraceState()))
                .getSpanContext().isValid()) {
            return null;
        }
        var descriptor = current.message().descriptor();
        var builder = GlobalOpenTelemetry.getTracer("io.github.ande1922.moduvera.messaging")
                .spanBuilder("outbox.prepare").setSpanKind(SpanKind.INTERNAL).setNoParent()
                .setAttribute("messaging.message.id", descriptor.id().value());
        var creation = descriptor.creationContext();
        if (creation != null) {
            var link = Span.fromContext(extract(creation.traceParent(), creation.traceState())).getSpanContext();
            if (link.isValid()) {
                builder.addLink(link);
            }
        }
        span = builder.startSpan();
        if (!span.getSpanContext().isValid()) {
            // Ordinary library tests have no governed SDK. Never invent a persisted trace identity.
            return null;
        }
        scope = span.makeCurrent();
        Map<String, String> values = new HashMap<>();
        PROPAGATOR.inject(Context.current(), values, Map::put);
        return new TraceContextCarrier(values.get("traceparent"), values.get("tracestate"));
    }

    void stored(ClaimedOutboxMessage current) {
        repaired = current;
    }

    void committed() {
        committed = true;
    }

    void requireCommitted() {
        if (!committed) {
            throw new IllegalStateException("outbox publication preparation did not commit");
        }
    }

    void recordRecovery() {
        if (repaired == null) {
            return;
        }
        var descriptor = repaired.message().descriptor();
        var diagnostic = DiagnosticLogSnapshot.captureWithoutIdentity(descriptor.correlationId())
                .withIdentity(descriptor.actor(), descriptor.initiator(), Optional.of(descriptor.tenantId()));
        try (var ignored = diagnostic.openFieldsScope()) {
            LOGGER.atWarn().addKeyValue("event.action", "outbox.publication.repair")
                    .addKeyValue("message_id", descriptor.id().value())
                    .addKeyValue("messaging.system", "kafka")
                    .addKeyValue("topic", descriptor.destination().value())
                    .log("Trace 连续性已中断，发布上下文已持久修复");
        }
    }

    void failed(Throwable failure) {
        if (span != null) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("error.type", failure.getClass().getName());
        }
    }

    @Override
    public void close() {
        try {
            if (scope != null) {
                scope.close();
            }
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }

    private static Context extract(String parent, String state) {
        Map<String, String> values = new HashMap<>();
        values.put("traceparent", parent);
        values.put("tracestate", state);
        return PROPAGATOR.extract(Context.root(), values, GETTER);
    }
}
