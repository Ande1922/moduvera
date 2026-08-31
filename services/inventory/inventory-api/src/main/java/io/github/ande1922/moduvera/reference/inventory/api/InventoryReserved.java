package io.github.ande1922.moduvera.reference.inventory.api;

import java.time.Instant;

public record InventoryReserved(String commandId, long orderId, Instant reservedAt)
        implements InventoryReservationResult {

    public InventoryReserved {
        if (commandId == null || commandId.isBlank() || orderId <= 0 || reservedAt == null) {
            throw new IllegalArgumentException("reserved result requires command, order and time");
        }
    }
}
