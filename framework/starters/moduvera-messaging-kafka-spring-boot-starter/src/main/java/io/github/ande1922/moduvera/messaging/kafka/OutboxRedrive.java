package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Method scopes are local; the actual database completion owns the redrive anchor's lifetime. */
final class OutboxRedrive implements AutoCloseable, TransactionSynchronization {
    private static final Logger LOGGER = LoggerFactory.getLogger("outbox.recovery");
    private Span span;
    private Scope scope;
    private DiagnosticLogSnapshot diagnostic;
    private DiagnosticLogSnapshot.Scope fields;
    private MessageDescriptor descriptor;
    private boolean registered;
    private boolean accepted;
    private boolean completed;
    private boolean committed;

    TraceContextCarrier start(MessageDescriptor original) {
        descriptor = original;
        var management = Span.current().getSpanContext();
        var builder = GlobalOpenTelemetry.getTracer("io.github.ande1922.moduvera.messaging")
                .spanBuilder("outbox.redrive").setSpanKind(SpanKind.INTERNAL).setNoParent()
                .setAttribute("messaging.message.id", original.id().value());
        var creation = original.creationContext();
        if (creation != null) {
            var link = Span.fromContext(OutboxPublicationPreparation.extract(creation.traceParent(), creation.traceState()))
                    .getSpanContext();
            if (link.isValid()) {
                builder.addLink(link);
            }
        }
        if (management.isValid()) {
            builder.addLink(management);
        }
        span = builder.startSpan();
        scope = Context.root().with(span).makeCurrent();
        diagnostic = DiagnosticLogSnapshot.captureWithoutIdentity(original.correlationId())
                .withIdentity(original.actor(), original.initiator(), Optional.of(original.tenantId()));
        fields = diagnostic.openFieldsScope();
        TransactionSynchronizationManager.registerSynchronization(this);
        registered = true;
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        String parent = carrier.get("traceparent");
        // No SDK: never carry the old generation into a newly accepted one or fabricate an identity.
        return parent == null ? null : new TraceContextCarrier(parent, carrier.get("tracestate"));
    }

    void accepted() {
        accepted = true;
    }

    boolean effectiveOrPending() {
        return accepted && (!completed || committed);
    }

    void failed(Throwable failure) {
        observe(() -> {
            if (span != null) {
                span.setStatus(StatusCode.ERROR);
                span.setAttribute("error.type", failure.getClass().getName());
            }
        });
    }

    @Override
    public void afterCommit() {
        if (accepted) {
            observe(() -> {
                try (var ignored = diagnostic.openScope()) {
                    LOGGER.atWarn().addKeyValue("message_id", descriptor.id().value())
                            .addKeyValue("messaging.system", "kafka")
                            .addKeyValue("topic", descriptor.destination().value())
                            .log("人工恢复已提交新的发布代次");
                }
            });
        }
    }

    @Override
    public void afterCompletion(int status) {
        completed = true;
        committed = status == STATUS_COMMITTED;
        observe(() -> {
            if (!committed || !accepted) {
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
        });
    }

    @Override
    public void close() {
        try {
            if (fields != null) {
                fields.close();
            }
        } finally {
            try {
                if (scope != null) {
                    scope.close();
                }
            } finally {
                if (!registered && span != null) {
                    observe(span::end);
                }
            }
        }
    }

    private static void observe(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            // Diagnostics must not replace a SQL failure or suppress the original committed wake signal.
        }
    }
}
