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

public final class InventoryReservationHandler
        implements ApplicationMessageHandler<ReserveInventoryCommand> {

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
        inbox.handle(messageId, () -> reserve(command));
    }

    private void reserve(ReserveInventoryCommand command) {
        ReservationExecution execution =
                inventory.reserve(command, clock.instant(), reservationPolicy);
        if (execution.created()) {
            publisher.publish(toIntegrationResult(execution));
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
