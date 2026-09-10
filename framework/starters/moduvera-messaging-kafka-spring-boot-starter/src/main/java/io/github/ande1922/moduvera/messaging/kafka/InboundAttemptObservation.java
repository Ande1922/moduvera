package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/** One local attempt beneath the Agent's current transport context, including Inbox lookup. */
final class InboundAttemptObservation implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("mq.consume");
    private static final TextMapGetter<TraceContextCarrier> CREATION_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(TraceContextCarrier carrier) {
            return List.of("traceparent", "tracestate");
        }

        @Override
        public String get(TraceContextCarrier carrier, String key) {
            if (carrier == null) {
                return null;
            }
            return switch (key) {
                case "traceparent" -> carrier.traceParent();
                case "tracestate" -> carrier.traceState();
                default -> null;
            };
        }
    };

    private final SerializedMessage message;
    private final int bodySize;
    private final int attempt;
    private final int maxAttempts;
    private final long started;
    private final Span span;
    private final io.opentelemetry.context.Scope spanScope;
    private final LoggingContextSnapshot.Scope loggingScope;
    private boolean success;
    private boolean retry;
    private String errorType;
    private boolean deferred;
    private double durationMillis;
    private final AtomicBoolean emitted = new AtomicBoolean();

    InboundAttemptObservation(SerializedMessage message, int bodySize, int attempt, int maxAttempts) {
        this.message = message;
        this.bodySize = bodySize;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        var builder = GlobalOpenTelemetry.getTracer("io.github.ande1922.moduvera.messaging")
                .spanBuilder("mq.process")
                .setSpanKind(SpanKind.INTERNAL);
        TraceContextCarrier creation = message.descriptor().creationContext();
        if (creation != null) {
            SpanContext link = Span.fromContext(W3CTraceContextPropagator.getInstance()
                            .extract(Context.root(), creation, CREATION_GETTER))
                    .getSpanContext();
            if (link.isValid()) {
                builder.addLink(link);
            }
        }
        span = builder.startSpan();
        spanScope = span.makeCurrent();
        loggingScope = LoggingContextSnapshot.capture().openScope();
        started = System.nanoTime();
    }

    void succeeded() {
        success = true;
    }

    void failed(Throwable failure, boolean willRetry) {
        errorType = failure.getClass().getName();
        retry = willRetry;
    }

    void defer() {
        deferred = true;
    }

    void complete(boolean deadLetter) {
        if (emitted.compareAndSet(false, true)) {
            var event = fields(LOGGER.atInfo())
                    .addKeyValue("event.outcome", success ? "success" : "failure")
                    .addKeyValue("duration_ms", durationMillis);
            if (deadLetter) {
                event.addKeyValue("disposition", "dead_letter");
            }
            event.log("消息处理结束");
        }
    }

    @Override
    public void close() {
        try {
            if (!success) {
                span.setStatus(StatusCode.ERROR);
            }
            durationMillis = (System.nanoTime() - started) / 1_000_000.0d;
            if (!deferred) {
                complete(false);
            }
            if (retry) {
                fields(LOGGER.atWarn()).log("消息处理失败，已决定重试");
            }
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

    private LoggingEventBuilder fields(LoggingEventBuilder event) {
        event.addKeyValue("messaging.system", "kafka")
                .addKeyValue("topic", message.descriptor().destination().value())
                .addKeyValue("message_id", message.descriptor().id().value())
                .addKeyValue("messaging.message.body.size", bodySize);
        if (attempt > 1 || retry) {
            event.addKeyValue("retry.attempt", attempt)
                    .addKeyValue("retry.max_attempts", maxAttempts);
        }
        if (retry) {
            event.addKeyValue("disposition", "retry");
        }
        if (errorType != null) {
            event.addKeyValue("error.type", errorType);
        }
        return event;
    }
}
