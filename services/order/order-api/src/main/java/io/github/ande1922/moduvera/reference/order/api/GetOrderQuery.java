package io.github.ande1922.moduvera.reference.order.api;

public record GetOrderQuery(long orderId) {

    public GetOrderQuery {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }
}
