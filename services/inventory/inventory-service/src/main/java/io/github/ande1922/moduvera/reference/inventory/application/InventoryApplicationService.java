package io.github.ande1922.moduvera.reference.inventory.application;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryApi;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import java.time.Clock;

public final class InventoryApplicationService implements InventoryApi {

    public static final PermissionCode RESERVE = new PermissionCode("inventory:reserve");

    private final InventoryStore inventory;
    private final UseCaseAuthorizer authorizer;
    private final Clock clock;
    private final InventoryResultPublisher publisher;

    public InventoryApplicationService(
            InventoryStore inventory,
            UseCaseAuthorizer authorizer,
            Clock clock,
            InventoryResultPublisher publisher) {
        this.inventory = inventory;
        this.authorizer = authorizer;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Override
    public InventoryReservationResult reserve(ReserveInventoryCommand command) {
        authorizer.require(RESERVE);
        var decision = inventory.reserve(command, clock.instant());
        if (decision.created()) {
            publisher.publish(decision.result());
        }
        return decision.result();
    }
}
