package io.github.ande1922.moduvera.reference.inventory.api;

import java.util.HashSet;
import java.util.List;

public record ReserveInventoryCommand(String commandId, long orderId, List<ReserveInventoryLine> lines) {

    public ReserveInventoryCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 128) {
            throw new IllegalArgumentException("commandId must be 1-128 characters");
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("at least one inventory line is required");
        }
        var productIds = new HashSet<Long>();
        if (lines.stream().anyMatch(line -> !productIds.add(line.productId()))) {
            throw new IllegalArgumentException("each product may appear only once in a reservation");
        }
    }
}
