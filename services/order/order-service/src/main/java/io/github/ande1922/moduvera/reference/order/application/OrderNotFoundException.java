package io.github.ande1922.moduvera.reference.order.application;

import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;

public final class OrderNotFoundException extends CodedException {

    public OrderNotFoundException(long orderId) {
        super(new ErrorCode("order.not-found"), "Order was not found: " + orderId);
    }
}
