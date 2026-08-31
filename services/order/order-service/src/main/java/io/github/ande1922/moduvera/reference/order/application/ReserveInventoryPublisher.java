package io.github.ande1922.moduvera.reference.order.application;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;

@FunctionalInterface
public interface ReserveInventoryPublisher {

    void publish(ReserveInventoryCommand command);
}
