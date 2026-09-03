package io.github.ande1922.moduvera.reference.inventory.domain;

import java.util.List;

@FunctionalInterface
public interface ReservationPolicy {

    ReservationDecision decide(
            List<ReservationRequestLine> requestedLines,
            List<StockAvailability> availableStocks);
}
