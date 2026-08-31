package io.github.ande1922.moduvera.reference.order.domain;

import io.github.ande1922.moduvera.reference.order.api.OrderLineView;
import java.math.BigDecimal;

public record OrderLine(long productId, String productName, int quantity, BigDecimal unitPrice) {

    public OrderLine {
        if (productId <= 0 || productName == null || productName.isBlank() || quantity <= 0) {
            throw new IllegalArgumentException("order line requires product identity, name and positive quantity");
        }
        if (unitPrice == null || unitPrice.signum() < 0 || unitPrice.scale() > 2) {
            throw new IllegalArgumentException("order line unit price is invalid");
        }
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public OrderLineView toView() {
        return new OrderLineView(productId, productName, quantity, unitPrice);
    }
}
