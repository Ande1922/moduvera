package io.github.ande1922.moduvera.reference.inventory.application.architecturefixture;

import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;

public final class RenamedInventoryProcessor implements ApplicationMessageHandler<String> {

    @Override
    public void handle(String payload, MessageId messageId) {
        payload.length();
    }
}
