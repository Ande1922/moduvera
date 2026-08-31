package io.github.ande1922.moduvera.reference.inventory.application;

import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;

@FunctionalInterface
public interface InventoryResultPublisher {

    void publish(InventoryReservationResult result);
}
