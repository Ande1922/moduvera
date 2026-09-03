package io.github.ande1922.moduvera.reference.inventory.domain;

import java.util.List;
import java.util.Objects;

public record ReservationDecision(
        Outcome outcome, List<Long> unavailableProductIds) {

    public enum Outcome {
        RESERVED,
        REJECTED
    }

    public ReservationDecision {
        Objects.requireNonNull(outcome, "outcome");
        unavailableProductIds = List.copyOf(unavailableProductIds);
        if (outcome == Outcome.RESERVED && !unavailableProductIds.isEmpty()) {
            throw new IllegalArgumentException("reserved decision cannot have unavailable products");
        }
        if (outcome == Outcome.REJECTED && unavailableProductIds.isEmpty()) {
            throw new IllegalArgumentException("rejected decision requires unavailable products");
        }
    }

    public static ReservationDecision reserved() {
        return new ReservationDecision(Outcome.RESERVED, List.of());
    }

    public static ReservationDecision rejected(List<Long> unavailableProductIds) {
        return new ReservationDecision(Outcome.REJECTED, unavailableProductIds);
    }

    public boolean isReserved() {
        return outcome == Outcome.RESERVED;
    }
}
