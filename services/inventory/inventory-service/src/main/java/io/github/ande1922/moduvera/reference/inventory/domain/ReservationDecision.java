package io.github.ande1922.moduvera.reference.inventory.domain;

import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import java.util.Objects;

public record ReservationDecision(InventoryReservationResult result, boolean created) {

    public ReservationDecision {
        Objects.requireNonNull(result, "result");
    }
}
