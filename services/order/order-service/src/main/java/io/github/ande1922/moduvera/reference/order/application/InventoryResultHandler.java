package io.github.ande1922.moduvera.reference.order.application;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;

public final class InventoryResultHandler
        implements ApplicationMessageHandler<InventoryReservationResult> {

    public static final PermissionCode APPLY_INVENTORY_RESULT =
            new PermissionCode("order:apply-inventory-result");

    private final InboxTemplate inbox;
    private final OrderRepository orders;
    private final UseCaseAuthorizer authorizer;

    public InventoryResultHandler(
            InboxTemplate inbox, OrderRepository orders, UseCaseAuthorizer authorizer) {
        this.inbox = inbox;
        this.orders = orders;
        this.authorizer = authorizer;
    }

    @Override
    public void handle(InventoryReservationResult result, MessageId messageId) {
        if (inbox.isProcessed(messageId)) {
            return;
        }
        authorizer.require(APPLY_INVENTORY_RESULT);
        inbox.handle(messageId, () -> apply(result));
    }

    private void apply(InventoryReservationResult result) {
        Order order = orders.findById(result.orderId())
                .orElseThrow(() -> new OrderNotFoundException(result.orderId()));
        if (result instanceof InventoryReserved reserved) {
            order.apply(reserved);
        } else if (result instanceof InventoryRejected rejected) {
            order.apply(rejected);
        }
        orders.save(order);
    }
}
