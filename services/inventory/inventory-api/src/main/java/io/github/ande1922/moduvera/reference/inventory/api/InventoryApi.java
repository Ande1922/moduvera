package io.github.ande1922.moduvera.reference.inventory.api;

public interface InventoryApi {

    InventoryReservationResult reserve(ReserveInventoryCommand command);
}
