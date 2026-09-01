package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.EventMessageHandler;

public final class RenamedInventoryEventProcessor implements EventMessageHandler {

    @Override
    public void handle(SerializedMessage message) {
        message.payload();
    }
}
