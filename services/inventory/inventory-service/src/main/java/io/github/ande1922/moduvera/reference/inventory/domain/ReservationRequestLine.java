package io.github.ande1922.moduvera.reference.inventory.domain;

public record ReservationRequestLine(long productId, int quantity) {

    public ReservationRequestLine {
        if (productId <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("productId and quantity must be positive");
        }
    }
}
