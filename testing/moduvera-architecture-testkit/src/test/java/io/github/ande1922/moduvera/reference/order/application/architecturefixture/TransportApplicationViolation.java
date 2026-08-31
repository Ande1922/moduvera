package io.github.ande1922.moduvera.reference.order.application.architecturefixture;

import org.springframework.messaging.Message;

public final class TransportApplicationViolation {

    public byte[] handle(Message<byte[]> message) {
        return message.getPayload();
    }
}
