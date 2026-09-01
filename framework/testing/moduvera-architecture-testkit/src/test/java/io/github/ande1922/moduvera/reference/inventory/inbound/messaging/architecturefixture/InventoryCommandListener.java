package io.github.ande1922.moduvera.reference.inventory.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.InventoryAllocationGateway;

public final class InventoryCommandListener {

    private final InventoryAllocationGateway inventory;

    public InventoryCommandListener(InventoryAllocationGateway inventory) {
        this.inventory = inventory;
    }

    public boolean accept(ReserveInventoryCommand command) {
        return inventory.allocate(command);
    }
}
