package io.github.ande1922.moduvera.reference.inventory.domain;

public record StockAvailability(long productId, int available) {

    public StockAvailability {
        if (productId <= 0 || available < 0) {
            throw new IllegalArgumentException("productId must be positive and available cannot be negative");
        }
    }
}
