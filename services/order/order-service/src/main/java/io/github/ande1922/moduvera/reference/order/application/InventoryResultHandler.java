package io.github.ande1922.moduvera.reference.order.application;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InventoryResultHandler
        implements ApplicationMessageHandler<InventoryReservationResult> {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryResultHandler.class);

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
        List<OrderStatus> changes = new ArrayList<>(1);
        inbox.handle(messageId, () -> apply(result, changes));
        // Inbox owns the complete top-level transaction, including the business change.
        for (OrderStatus status : changes) {
            LOG.atInfo()
                    .addKeyValue("event.action", status == OrderStatus.CONFIRMED
                            ? "order_confirmed" : "order_rejected")
                    .addKeyValue("order_id", result.orderId())
                    .log("订单库存结果已生效");
        }
    }

    private void apply(InventoryReservationResult result, List<OrderStatus> changes) {
        Order order = orders.findById(result.orderId())
                .orElseThrow(() -> new OrderNotFoundException(result.orderId()));
        OrderStatus previous = order.status();
        if (result instanceof InventoryReserved reserved) {
            order.apply(reserved);
        } else if (result instanceof InventoryRejected rejected) {
            order.apply(rejected);
        }
        orders.save(order);
        if (previous != order.status()) {
            changes.add(order.status());
        }
    }
}
