package io.github.ande1922.moduvera.reference.inventory.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AllOrNothingReservationPolicy implements ReservationPolicy {

    @Override
    public ReservationDecision decide(
            List<ReservationRequestLine> requestedLines,
            List<StockAvailability> availableStocks) {
        List<ReservationRequestLine> lines = List.copyOf(requestedLines);
        List<StockAvailability> stocks = List.copyOf(availableStocks);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("at least one inventory line is required");
        }

        Map<Long, StockAvailability> stockByProduct = stocks.stream()
                .collect(Collectors.toUnmodifiableMap(
                        StockAvailability::productId, Function.identity()));
        List<Long> unavailable = lines.stream()
                .filter(line -> {
                    StockAvailability stock = stockByProduct.get(line.productId());
                    return stock == null || stock.available() < line.quantity();
                })
                .map(ReservationRequestLine::productId)
                .sorted()
                .toList();
        return unavailable.isEmpty()
                ? ReservationDecision.reserved()
                : ReservationDecision.rejected(unavailable);
    }
}
