package io.github.ande1922.moduvera.reference.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

public record OrderView(
        long orderId,
        OrderStatus status,
        List<OrderLineView> lines,
        BigDecimal total,
        Currency currency,
        Instant placedAt) {

    public OrderView {
        lines = List.copyOf(lines);
    }
}
