package io.github.ande1922.moduvera.reference.inventory.domain;

import java.time.Instant;
import java.util.Objects;

public record ReservationExecution(
        String commandId,
        long orderId,
        ReservationDecision decision,
        Instant decidedAt,
        boolean created) {

    public ReservationExecution {
        if (commandId == null || commandId.isBlank() || orderId <= 0) {
            throw new IllegalArgumentException("reservation execution requires command and order");
        }
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }
}
