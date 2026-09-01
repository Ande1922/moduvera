package io.github.ande1922.moduvera.reference.inventory.api;

public sealed interface InventoryReservationResult permits InventoryReserved, InventoryRejected {

    String MESSAGE_KIND = "EVENT";
    String MESSAGE_TYPE =
            "io.github.ande1922.moduvera.reference.inventory.reservation-result.v1";
    String DESTINATION = "order.inventory-result";

    String commandId();

    long orderId();
}
