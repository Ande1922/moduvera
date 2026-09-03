package io.github.ande1922.moduvera.reference.inventory.domain;

import java.time.Instant;

public interface InventoryStore {

    ReservationExecution reserve(
            ReservationRequest request, Instant now, ReservationPolicy policy);
}
