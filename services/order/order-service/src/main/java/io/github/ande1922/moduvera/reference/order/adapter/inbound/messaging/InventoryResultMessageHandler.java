package io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.EventMessageHandler;
import io.github.ande1922.moduvera.reference.order.application.OrderApplicationService;

final class InventoryResultMessageHandler implements EventMessageHandler {

    private final OrderApplicationService orders;
    private final InventoryResultMessageMapper mapper;

    InventoryResultMessageHandler(
            OrderApplicationService orders, InventoryResultMessageMapper mapper) {
        this.orders = orders;
        this.mapper = mapper;
    }

    @Override
    public void handle(SerializedMessage message) {
        orders.resolvePendingStock(mapper.map(message));
    }
}
