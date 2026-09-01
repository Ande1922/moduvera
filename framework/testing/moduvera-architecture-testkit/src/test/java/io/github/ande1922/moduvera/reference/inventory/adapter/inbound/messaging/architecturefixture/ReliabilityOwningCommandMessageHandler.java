package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.CommandMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;

public final class ReliabilityOwningCommandMessageHandler implements CommandMessageHandler {

    private final InboxTemplate inbox;

    public ReliabilityOwningCommandMessageHandler(InboxTemplate inbox) {
        this.inbox = inbox;
    }

    @Override
    public void handle(SerializedMessage message) {
        inbox.handle(message.descriptor().id(), () -> {});
    }
}
