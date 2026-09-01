package io.github.ande1922.moduvera.reference.order.application.architecturefixture;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.EventMessageHandler;

public final class HandlerApplicationServiceViolation implements EventMessageHandler {

    @Override
    public void handle(SerializedMessage message) {
        message.payload();
    }
}
