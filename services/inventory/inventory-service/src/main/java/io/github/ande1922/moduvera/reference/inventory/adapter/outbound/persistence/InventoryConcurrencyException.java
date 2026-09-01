package io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence;

import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;

public final class InventoryConcurrencyException extends CodedException {

    public InventoryConcurrencyException(long productId) {
        super(
                new ErrorCode("inventory.concurrent-modification"),
                "Inventory was concurrently changed for product " + productId);
    }
}
