package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.publication.ImmediatePublication;
import io.github.ande1922.moduvera.message.publication.ImmediatePublicationInTransactionException;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class KafkaImmediatePublication implements ImmediatePublication {

    private final MessageTransport transport;

    public KafkaImmediatePublication(MessageTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public void publish(SerializedMessage message) {
        Objects.requireNonNull(message, "message");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ImmediatePublicationInTransactionException();
        }
        transport.send(message);
    }
}
