package io.github.ande1922.moduvera.reference.inventory.architecturefixture;

import org.springframework.messaging.Message;

public final class MisplacedInventoryMessageHandler {

    public byte[] handle(Message<byte[]> message) {
        return message.getPayload();
    }
}
