package io.github.ande1922.moduvera.reference.order.api;

import java.util.HashSet;
import java.util.List;

public record CreateOrderCommand(List<CreateOrderLine> lines) {

    public CreateOrderCommand {
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("at least one order line is required");
        }
        var productIds = new HashSet<Long>();
        if (lines.stream().anyMatch(line -> !productIds.add(line.productId()))) {
            throw new IllegalArgumentException("each product may appear only once in an order");
        }
    }
}
