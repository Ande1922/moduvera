package io.github.ande1922.moduvera.reference.inventory.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.InventoryReservationService;

public final class ReservationCommandConsumer {

    private final InventoryReservationService inventory;

    public ReservationCommandConsumer(InventoryReservationService inventory) {
        this.inventory = inventory;
    }

    public void accept(ReserveInventoryCommand command) {
        inventory.execute(command);
    }
}
