package io.github.ande1922.moduvera.reference.inventory.application;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationExecution;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationPolicy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InventoryReservationHandler
        implements ApplicationMessageHandler<ReserveInventoryCommand> {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryReservationHandler.class);

    public static final PermissionCode RESERVE = new PermissionCode("inventory:reserve");

    private final InboxTemplate inbox;
    private final InventoryStore inventory;
    private final UseCaseAuthorizer authorizer;
    private final Clock clock;
    private final InventoryResultPublisher publisher;
    private final ReservationPolicy reservationPolicy;

    public InventoryReservationHandler(
            InboxTemplate inbox,
            InventoryStore inventory,
            UseCaseAuthorizer authorizer,
            Clock clock,
            InventoryResultPublisher publisher,
            ReservationPolicy reservationPolicy) {
        this.inbox = inbox;
        this.inventory = inventory;
        this.authorizer = authorizer;
        this.clock = clock;
        this.publisher = publisher;
        this.reservationPolicy = reservationPolicy;
    }

    @Override
    public void handle(ReserveInventoryCommand command, MessageId messageId) {
        if (inbox.isProcessed(messageId)) {
            return;
        }
        authorizer.require(RESERVE);
        List<ReservationExecution> decisions = new ArrayList<>(1);
        inbox.handle(messageId, () -> reserve(command, decisions));
        // Only a committed new decision is recorded; both Inbox and business duplicates are silent.
        decisions.forEach(InventoryReservationHandler::logDecision);
    }

    private void reserve(ReserveInventoryCommand command, List<ReservationExecution> decisions) {
        ReservationExecution execution =
                inventory.reserve(command, clock.instant(), reservationPolicy);
        if (execution.created()) {
            publisher.publish(toIntegrationResult(execution));
            decisions.add(execution);
        }
    }

    private static void logDecision(ReservationExecution execution) {
        var event = LOG.atInfo().addKeyValue("order_id", execution.orderId());
        if (execution.decision().isReserved()) {
            event.addKeyValue("event.action", "inventory_reserved").log("库存预留成功");
        } else {
            event.addKeyValue("error.code", "BIZ_INVENTORY_STOCK_UNAVAILABLE").log("库存不足，预留已拒绝");
        }
    }

    private static InventoryReservationResult toIntegrationResult(ReservationExecution execution) {
        if (execution.decision().isReserved()) {
            return new InventoryReserved(
                    execution.commandId(), execution.orderId(), execution.decidedAt());
        }
        return new InventoryRejected(
                execution.commandId(),
                execution.orderId(),
                execution.decision().unavailableProductIds(),
                execution.decidedAt());
    }
}
