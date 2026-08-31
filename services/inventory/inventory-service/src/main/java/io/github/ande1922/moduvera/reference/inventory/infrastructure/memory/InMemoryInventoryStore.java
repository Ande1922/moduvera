package io.github.ande1922.moduvera.reference.inventory.infrastructure.memory;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class InMemoryInventoryStore implements InventoryStore {

    private final Map<StockKey, Integer> available = new HashMap<>();
    private final Map<CommandKey, InventoryReservationResult> completed = new HashMap<>();

    public synchronized void setAvailable(long productId, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must not be negative");
        }
        available.put(new StockKey(currentTenant(), productId), quantity);
    }

    public synchronized int available(long productId) {
        return available.getOrDefault(new StockKey(currentTenant(), productId), 0);
    }

    @Override
    public synchronized ReservationDecision reserve(ReserveInventoryCommand command, Instant now) {
        TenantId tenantId = currentTenant();
        CommandKey commandKey = new CommandKey(tenantId, command.commandId());
        InventoryReservationResult previous = completed.get(commandKey);
        if (previous != null) {
            return new ReservationDecision(previous, false);
        }

        var unavailable = command.lines().stream()
                .filter(line -> available(line.productId()) < line.quantity())
                .map(line -> line.productId())
                .sorted()
                .toList();
        InventoryReservationResult result;
        if (unavailable.isEmpty()) {
            command.lines().forEach(line -> available.compute(
                    new StockKey(tenantId, line.productId()),
                    (ignored, quantity) -> quantity - line.quantity()));
            result = new InventoryReserved(command.commandId(), command.orderId(), now);
        } else {
            result = new InventoryRejected(command.commandId(), command.orderId(), unavailable, now);
        }
        completed.put(commandKey, result);
        return new ReservationDecision(result, true);
    }

    private static TenantId currentTenant() {
        return ExecutionContextHolder.require().tenantId();
    }

    private record StockKey(TenantId tenantId, long productId) {}

    private record CommandKey(TenantId tenantId, String commandId) {}
}
