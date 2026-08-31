package io.github.ande1922.moduvera.reference.catalog.api;

import java.math.BigDecimal;
import java.util.Currency;

public record ProductSnapshot(
        long productId, String name, BigDecimal unitPrice, Currency currency, long version) {

    public ProductSnapshot {
        if (productId <= 0 || version < 0) {
            throw new IllegalArgumentException("product identity must be positive and version must not be negative");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("product name must not be blank");
        }
        if (unitPrice == null || unitPrice.signum() < 0 || unitPrice.scale() > 2) {
            throw new IllegalArgumentException("unit price must be non-negative with at most two decimals");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
    }
}
