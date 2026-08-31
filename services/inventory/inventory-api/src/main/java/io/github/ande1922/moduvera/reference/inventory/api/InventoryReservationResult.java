package io.github.ande1922.moduvera.reference.inventory.api;

public sealed interface InventoryReservationResult permits InventoryReserved, InventoryRejected {

    String commandId();

    long orderId();
}
