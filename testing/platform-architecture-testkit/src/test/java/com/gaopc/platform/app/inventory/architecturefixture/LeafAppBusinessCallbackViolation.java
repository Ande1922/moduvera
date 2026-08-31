package com.gaopc.platform.app.inventory.architecturefixture;

import com.gaopc.platform.inventory.application.InventoryResultPublisher;

public final class LeafAppBusinessCallbackViolation {

    private final InventoryResultPublisher publisher;

    public LeafAppBusinessCallbackViolation(InventoryResultPublisher publisher) {
        this.publisher = publisher;
    }

    public InventoryResultPublisher publisher() {
        return publisher;
    }
}
