package io.github.ande1922.moduvera.reference.inventory.domain;

import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AllOrNothingReservationPolicy implements ReservationPolicy {

    @Override
    public ReservationDecision decide(
            ReserveInventoryCommand request, List<StockAvailability> availableStocks) {
        List<ReserveInventoryLine> lines = request.lines();
        List<StockAvailability> stocks = List.copyOf(availableStocks);

        Map<Long, StockAvailability> stockByProduct = stocks.stream()
                .collect(Collectors.toUnmodifiableMap(
                        StockAvailability::productId, Function.identity()));
        List<Long> unavailable = lines.stream()
                .filter(line -> {
                    StockAvailability stock = stockByProduct.get(line.productId());
                    return stock == null || stock.available() < line.quantity();
                })
                .map(ReserveInventoryLine::productId)
                .sorted()
                .toList();
        return unavailable.isEmpty()
                ? ReservationDecision.reserved()
                : ReservationDecision.rejected(unavailable);
    }
}
