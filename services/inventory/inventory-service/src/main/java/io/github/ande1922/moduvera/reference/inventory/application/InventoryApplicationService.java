package io.github.ande1922.moduvera.reference.inventory.application;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationExecution;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationPolicy;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationRequest;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationRequestLine;
import java.time.Clock;

public final class InventoryApplicationService {

    public static final PermissionCode RESERVE = new PermissionCode("inventory:reserve");

    private final InventoryStore inventory;
    private final UseCaseAuthorizer authorizer;
    private final Clock clock;
    private final InventoryResultPublisher publisher;
    private final ReservationPolicy reservationPolicy;

    public InventoryApplicationService(
            InventoryStore inventory,
            UseCaseAuthorizer authorizer,
            Clock clock,
            InventoryResultPublisher publisher,
            ReservationPolicy reservationPolicy) {
        this.inventory = inventory;
        this.authorizer = authorizer;
        this.clock = clock;
        this.publisher = publisher;
        this.reservationPolicy = reservationPolicy;
    }

    public InventoryReservationResult reserve(ReserveInventoryCommand command) {
        authorizer.require(RESERVE);
        var request = new ReservationRequest(
                command.commandId(),
                command.orderId(),
                command.lines().stream()
                        .map(line -> new ReservationRequestLine(
                                line.productId(), line.quantity()))
                        .toList());
        ReservationExecution execution =
                inventory.reserve(request, clock.instant(), reservationPolicy);
        InventoryReservationResult result = toIntegrationResult(execution);
        if (execution.created()) {
            publisher.publish(result);
        }
        return result;
    }

    private static InventoryReservationResult toIntegrationResult(
            ReservationExecution execution) {
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
