package io.github.ande1922.moduvera.reference.catalog.api;

public record GetProductQuery(long productId) {

    public GetProductQuery {
        if (productId <= 0) {
            throw new IllegalArgumentException("productId must be positive");
        }
    }
}
