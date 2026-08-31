package io.github.ande1922.moduvera.reference.order.design.domain;

public record AggregateVersion(long value) {

    public AggregateVersion {
        if (value < 0) {
            throw new IllegalArgumentException("aggregate version must not be negative");
        }
    }

    public AggregateVersion next() {
        return new AggregateVersion(Math.incrementExact(value));
    }
}
