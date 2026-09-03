package io.github.ande1922.moduvera.reference.inventory.domain;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import java.util.List;

@FunctionalInterface
public interface ReservationPolicy {

    ReservationDecision decide(
            ReserveInventoryCommand request, List<StockAvailability> availableStocks);
}
