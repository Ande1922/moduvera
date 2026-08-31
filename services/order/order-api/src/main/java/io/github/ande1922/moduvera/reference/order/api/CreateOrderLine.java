package io.github.ande1922.moduvera.reference.order.api;

public record CreateOrderLine(long productId, int quantity) {

    public CreateOrderLine {
        if (productId <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("productId and quantity must be positive");
        }
    }
}
