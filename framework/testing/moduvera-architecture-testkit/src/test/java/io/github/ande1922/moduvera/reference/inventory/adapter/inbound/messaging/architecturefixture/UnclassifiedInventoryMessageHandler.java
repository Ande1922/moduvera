package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.InboundMessageHandler;

public final class UnclassifiedInventoryMessageHandler implements InboundMessageHandler {

    @Override
    public void handle(SerializedMessage message) {
        message.payload();
    }
}
