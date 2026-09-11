package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.publication.ImmediatePublication;
import io.github.ande1922.moduvera.message.publication.ImmediatePublicationInTransactionException;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import io.opentelemetry.context.Context;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class KafkaImmediatePublication implements ImmediatePublication {

    private static final Logger LOGGER = LoggerFactory.getLogger("mq.produce");
    private final MessageTransport transport;
    private final Set<String> businessBoundaryDestinations;

    public KafkaImmediatePublication(MessageTransport transport) {
        this(transport, Set.of());
    }

    public KafkaImmediatePublication(MessageTransport transport, Set<String> businessBoundaryDestinations) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.businessBoundaryDestinations = Set.copyOf(businessBoundaryDestinations);
    }

    @Override
    public void publish(SerializedMessage message) {
        Objects.requireNonNull(message, "message");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ImmediatePublicationInTransactionException();
        }
        SerializedMessage outgoing = withCreationContext(message);
        long started = System.nanoTime();
        try (var ignored = LoggingContextSnapshot.captureAllowingAbsent().openScope();
                var send = ImmediateProducerDiagnostics.open()) {
            try {
                transport.send(outgoing);
            } catch (RuntimeException | Error failure) {
                result(outgoing, started, failure);
                throw failure;
            }
            if (businessBoundaryDestinations.contains(outgoing.descriptor().destination().value())) {
                result(outgoing, started, null);
            }
        }
    }

    private static SerializedMessage withCreationContext(SerializedMessage message) {
        MessageDescriptor descriptor = message.descriptor();
        if (descriptor.creationContext() != null) {
            return message;
        }
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        String traceParent = carrier.get("traceparent");
        if (traceParent == null) {
            return message;
        }
        var creation = new TraceContextCarrier(traceParent, carrier.get("tracestate"));
        return new SerializedMessage(new MessageDescriptor(
                descriptor.id(), descriptor.kind(), descriptor.type(), descriptor.source(),
                descriptor.destination(), descriptor.time(), descriptor.tenantId(), descriptor.actor(),
                descriptor.correlationId(), descriptor.causationId(), descriptor.initiator(),
                descriptor.partitionKey(), creation), message.contentType(), message.payload());
    }

    private static void result(SerializedMessage message, long started, Throwable failure) {
        var event = LOGGER.atInfo()
                .addKeyValue("event.outcome", failure == null ? "success" : "failure")
                .addKeyValue("messaging.system", "kafka")
                .addKeyValue("topic", message.descriptor().destination().value())
                .addKeyValue("message_id", message.descriptor().id().value())
                .addKeyValue("messaging.message.body.size", message.payload().length)
                .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0d);
        if (failure != null) {
            event.setCause(failure);
        }
        event.log("消息发布调用结束");
    }
}
