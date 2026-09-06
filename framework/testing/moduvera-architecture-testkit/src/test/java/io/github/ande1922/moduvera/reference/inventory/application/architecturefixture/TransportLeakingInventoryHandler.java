package io.github.ande1922.moduvera.reference.inventory.application.architecturefixture;

import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;

public final class TransportLeakingInventoryHandler
        implements ApplicationMessageHandler<SerializedMessage> {

    @Override
    public void handle(SerializedMessage payload, MessageId messageId) {
        payload.payload();
    }
}
