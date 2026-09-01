package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging;

import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.CommandMessageHandler;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class ReserveInventoryCommandMessageHandler implements CommandMessageHandler {

    private final InventoryApplicationService inventory;
    private final ObjectMapper json;

    ReserveInventoryCommandMessageHandler(
            InventoryApplicationService inventory, ObjectMapper json) {
        this.inventory = inventory;
        this.json = json;
    }

    @Override
    public void handle(SerializedMessage message) {
        try {
            inventory.reserve(json.readValue(message.payload(), ReserveInventoryCommand.class));
        } catch (JacksonException invalid) {
            throw new NonRetryableMessageException("invalid reserve inventory command", invalid);
        }
    }
}
