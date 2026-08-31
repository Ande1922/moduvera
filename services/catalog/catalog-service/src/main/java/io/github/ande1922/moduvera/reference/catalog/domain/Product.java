package io.github.ande1922.moduvera.reference.catalog.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public final class Product {

    private final long id;
    private final String name;
    private final BigDecimal price;
    private final Currency currency;
    private final long version;

    public Product(
            long id,
            String name,
            BigDecimal price,
            Currency currency,
            long version) {
        if (id <= 0 || version < 0) {
            throw new IllegalArgumentException("product identity must be positive and version must not be negative");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("product name must not be blank");
        }
        if (price == null || price.signum() < 0 || price.scale() > 2) {
            throw new IllegalArgumentException("product price must be non-negative with at most two decimals");
        }
        this.id = id;
        this.name = name;
        this.price = price;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.version = version;
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public BigDecimal price() {
        return price;
    }

    public Currency currency() {
        return currency;
    }

    public long version() {
        return version;
    }

    public io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot snapshot() {
        return new io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot(id, name, price, currency, version);
    }
}
