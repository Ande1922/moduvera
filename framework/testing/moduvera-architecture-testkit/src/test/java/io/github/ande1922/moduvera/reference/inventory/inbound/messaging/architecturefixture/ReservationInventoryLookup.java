package io.github.ande1922.moduvera.reference.inventory.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.InventoryLookupService;

public final class ReservationInventoryLookup {

    private final InventoryLookupService inventory;

    public ReservationInventoryLookup(InventoryLookupService inventory) {
        this.inventory = inventory;
    }

    public boolean firstLineIsAvailable(ReserveInventoryCommand command) {
        return inventory.isAvailable(command.lines().getFirst().productId());
    }
}
