package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging;

import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumer;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import org.springframework.messaging.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class ReserveInventoryCommandMessageConsumer {

    private final ReliableMessageConsumer reliableConsumer;
    private final InventoryApplicationService inventory;
    private final ObjectMapper json;

    ReserveInventoryCommandMessageConsumer(
            ReliableMessageConsumer reliableConsumer,
            InventoryApplicationService inventory,
            ObjectMapper json) {
        this.reliableConsumer = reliableConsumer;
        this.inventory = inventory;
        this.json = json;
    }

    void accept(Message<byte[]> message) {
        reliableConsumer.handle(message, this::reserve);
    }

    private void reserve(SerializedMessage serialized) {
        try {
            inventory.reserve(json.readValue(serialized.payload(), ReserveInventoryCommand.class));
        } catch (JacksonException invalid) {
            throw new NonRetryableMessageException("invalid reserve inventory command", invalid);
        }
    }
}
