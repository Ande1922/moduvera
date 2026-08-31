package com.gaopc.platform.inventory.architecturefixture;

import org.springframework.messaging.Message;

public final class MisplacedInventoryMessageHandler {

    public byte[] handle(Message<byte[]> message) {
        return message.getPayload();
    }
}
