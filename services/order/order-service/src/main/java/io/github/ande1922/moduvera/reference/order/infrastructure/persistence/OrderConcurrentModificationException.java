package io.github.ande1922.moduvera.reference.order.infrastructure.persistence;

import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;

public final class OrderConcurrentModificationException extends CodedException {

    public OrderConcurrentModificationException(long orderId) {
        super(new ErrorCode("order.concurrent-modification"), "Order was concurrently changed: " + orderId);
    }
}
