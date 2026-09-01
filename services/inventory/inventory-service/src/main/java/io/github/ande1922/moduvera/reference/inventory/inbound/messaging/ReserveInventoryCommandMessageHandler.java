package io.github.ande1922.moduvera.reference.inventory.inbound.messaging;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class ReserveInventoryCommandMessageHandler {

    private final InventoryApplicationService inventory;
    private final ObjectMapper json;

    ReserveInventoryCommandMessageHandler(InventoryApplicationService inventory, ObjectMapper json) {
        this.inventory = inventory;
        this.json = json;
    }

    void handle(SerializedMessage serialized) {
        try {
            inventory.reserve(json.readValue(serialized.payload(), ReserveInventoryCommand.class));
        } catch (JacksonException invalid) {
            throw new NonRetryableMessageException("invalid reserve inventory command", invalid);
        }
    }
}
