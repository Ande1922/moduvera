package io.github.ande1922.moduvera.reference.inventory.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.SynchronousInventoryApi;

public final class InventoryReservationInboundAdapter {

    private final SynchronousInventoryApi inventory;

    public InventoryReservationInboundAdapter(SynchronousInventoryApi inventory) {
        this.inventory = inventory;
    }

    public void accept(ReserveInventoryCommand command) {
        inventory.reserve(command);
    }
}
