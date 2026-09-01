package io.github.ande1922.moduvera.reference.inventory.api.architecturefixture;

import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;

public interface SynchronousInventoryApi {

    InventoryReservationResult reserve(ReserveInventoryCommand command);
}
