package io.github.ande1922.moduvera.reference.inventory.api.architecturefixture;

import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;

public interface InventoryReservationService {

    InventoryReservationResult execute(ReserveInventoryCommand command);
}
