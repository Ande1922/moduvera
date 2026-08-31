package com.gaopc.platform.order.application.architecturefixture;

import org.springframework.messaging.Message;

public final class TransportApplicationViolation {

    public byte[] handle(Message<byte[]> message) {
        return message.getPayload();
    }
}
