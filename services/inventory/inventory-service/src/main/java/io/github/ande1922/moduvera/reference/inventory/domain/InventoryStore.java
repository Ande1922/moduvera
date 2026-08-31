package io.github.ande1922.moduvera.reference.inventory.domain;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import java.time.Instant;

public interface InventoryStore {

    ReservationDecision reserve(ReserveInventoryCommand command, Instant now);
}
