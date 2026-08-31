package com.gaopc.platform.inventory.inbound.messaging;

import com.gaopc.platform.inventory.api.InventoryApi;
import com.gaopc.platform.inventory.api.ReserveInventoryCommand;
import com.gaopc.platform.message.NonRetryableMessageException;
import com.gaopc.platform.message.SerializedMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class ReserveInventoryCommandMessageHandler {

    private final InventoryApi inventory;
    private final ObjectMapper json;

    ReserveInventoryCommandMessageHandler(InventoryApi inventory, ObjectMapper json) {
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
