package io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationExecution;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationPolicy;
import io.github.ande1922.moduvera.reference.inventory.domain.StockAvailability;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MybatisInventoryStore implements InventoryStore {

    private final InventoryMapper mapper;

    public MybatisInventoryStore(InventoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReservationExecution reserve(
            ReserveInventoryCommand request, Instant now, ReservationPolicy policy) {
        var context = ExecutionContextHolder.require();
        String tenantId = context.requireTenantId().value();
        InventoryResultRow previous = mapper.findResult(tenantId, request.commandId());
        if (previous != null) {
            return toExecution(previous, false);
        }

        var orderedLines = request.lines().stream()
                .sorted(java.util.Comparator.comparingLong(line -> line.productId()))
                .toList();
        Map<Long, InventoryStockRow> locked = mapper.lockStocks(
                        tenantId, orderedLines.stream().map(line -> line.productId()).toList())
                .stream()
                .collect(Collectors.toMap(InventoryStockRow::getProductId, Function.identity()));

        previous = mapper.findResult(tenantId, request.commandId());
        if (previous != null) {
            return toExecution(previous, false);
        }

        ReservationDecision decision = policy.decide(
                request,
                locked.values().stream()
                        .map(stock -> new StockAvailability(
                                stock.getProductId(), stock.getAvailable()))
                        .toList());
        if (decision.isReserved()) {
            for (var line : orderedLines) {
                InventoryStockRow stock = locked.get(line.productId());
                int updated = mapper.reserve(
                        tenantId,
                        line.productId(),
                        line.quantity(),
                        stock.getVersion(),
                        now,
                        context.actor().subjectId());
                if (updated != 1) {
                    throw new InventoryConcurrencyException(line.productId());
                }
            }
        }

        String unavailableProductIds = decision.unavailableProductIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        int inserted = mapper.insertResult(
                tenantId,
                request.commandId(),
                request.orderId(),
                decision.outcome().name(),
                unavailableProductIds,
                now,
                context.actor().subjectId());
        if (inserted != 1) {
            throw new IllegalStateException("reservation result raced after stock locks");
        }
        return new ReservationExecution(
                request.commandId(), request.orderId(), decision, now, true);
    }

    private static ReservationExecution toExecution(
            InventoryResultRow row, boolean created) {
        ReservationDecision decision;
        if (ReservationDecision.Outcome.RESERVED.name().equals(row.getResultType())) {
            decision = ReservationDecision.reserved();
        } else if (ReservationDecision.Outcome.REJECTED.name().equals(row.getResultType())) {
            List<Long> unavailable = Arrays.stream(row.getUnavailableProductIds().split(","))
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();
            decision = ReservationDecision.rejected(unavailable);
        } else {
            throw new IllegalStateException(
                    "unknown persisted inventory result type: " + row.getResultType());
        }
        return new ReservationExecution(
                row.getCommandId(), row.getOrderId(), decision, row.getDecidedAt(), created);
    }
}
