package io.github.ande1922.moduvera.reference.order.application;

import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;

@FunctionalInterface
public interface InventoryResultHandler {

    void handle(InventoryReservationResult result);
}
