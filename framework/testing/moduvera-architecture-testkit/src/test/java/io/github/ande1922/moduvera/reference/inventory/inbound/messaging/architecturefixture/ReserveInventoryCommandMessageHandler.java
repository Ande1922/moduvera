package io.github.ande1922.moduvera.reference.inventory.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.SynchronousInventoryApi;

public final class ReserveInventoryCommandMessageHandler {

    private final SynchronousInventoryApi inventory;

    public ReserveInventoryCommandMessageHandler(SynchronousInventoryApi inventory) {
        this.inventory = inventory;
    }

    public void handle(ReserveInventoryCommand command) {
        inventory.reserve(command);
    }
}
