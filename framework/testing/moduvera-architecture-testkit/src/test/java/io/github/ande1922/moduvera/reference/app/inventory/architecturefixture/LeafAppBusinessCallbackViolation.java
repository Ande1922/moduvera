package io.github.ande1922.moduvera.reference.app.inventory.architecturefixture;

import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;

public final class LeafAppBusinessCallbackViolation {

    private final InventoryResultPublisher publisher;

    public LeafAppBusinessCallbackViolation(InventoryResultPublisher publisher) {
        this.publisher = publisher;
    }

    public InventoryResultPublisher publisher() {
        return publisher;
    }
}
