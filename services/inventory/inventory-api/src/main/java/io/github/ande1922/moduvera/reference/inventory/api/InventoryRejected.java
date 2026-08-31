package io.github.ande1922.moduvera.reference.inventory.api;

import java.time.Instant;
import java.util.List;

public record InventoryRejected(
        String commandId, long orderId, List<Long> unavailableProductIds, Instant rejectedAt)
        implements InventoryReservationResult {

    public InventoryRejected {
        if (commandId == null || commandId.isBlank() || orderId <= 0 || rejectedAt == null) {
            throw new IllegalArgumentException("rejected result requires command, order and time");
        }
        unavailableProductIds = List.copyOf(unavailableProductIds);
        if (unavailableProductIds.isEmpty()) {
            throw new IllegalArgumentException("rejected result requires at least one unavailable product");
        }
    }
}
