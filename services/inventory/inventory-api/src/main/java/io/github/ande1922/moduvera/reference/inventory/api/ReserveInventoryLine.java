package io.github.ande1922.moduvera.reference.inventory.api;

public record ReserveInventoryLine(long productId, int quantity) {

    public ReserveInventoryLine {
        if (productId <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("productId and quantity must be positive");
        }
    }
}
