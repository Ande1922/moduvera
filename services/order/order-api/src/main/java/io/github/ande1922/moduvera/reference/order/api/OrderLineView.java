package io.github.ande1922.moduvera.reference.order.api;

import java.math.BigDecimal;

public record OrderLineView(long productId, String productName, int quantity, BigDecimal unitPrice) {}
