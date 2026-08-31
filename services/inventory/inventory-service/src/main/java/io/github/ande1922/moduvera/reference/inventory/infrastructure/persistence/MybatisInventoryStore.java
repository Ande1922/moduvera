package io.github.ande1922.moduvera.reference.inventory.infrastructure.persistence;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
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
    public ReservationDecision reserve(ReserveInventoryCommand command, Instant now) {
        var context = ExecutionContextHolder.require();
        String tenantId = context.tenantId().value();
        InventoryResultRow previous = mapper.findResult(tenantId, command.commandId());
        if (previous != null) {
            return new ReservationDecision(toResult(previous), false);
        }

        var orderedLines = command.lines().stream()
                .sorted(java.util.Comparator.comparingLong(line -> line.productId()))
                .toList();
        Map<Long, InventoryStockRow> locked = mapper.lockStocks(
                        tenantId, orderedLines.stream().map(line -> line.productId()).toList())
                .stream()
                .collect(Collectors.toMap(InventoryStockRow::getProductId, Function.identity()));

        previous = mapper.findResult(tenantId, command.commandId());
        if (previous != null) {
            return new ReservationDecision(toResult(previous), false);
        }

        List<Long> unavailable = orderedLines.stream()
                .filter(line -> {
                    InventoryStockRow stock = locked.get(line.productId());
                    return stock == null || stock.getAvailable() < line.quantity();
                })
                .map(line -> line.productId())
                .toList();
        InventoryReservationResult result;
        if (unavailable.isEmpty()) {
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
            result = new InventoryReserved(command.commandId(), command.orderId(), now);
        } else {
            result = new InventoryRejected(command.commandId(), command.orderId(), unavailable, now);
        }

        int inserted = mapper.insertResult(
                tenantId,
                command.commandId(),
                command.orderId(),
                result instanceof InventoryReserved ? "RESERVED" : "REJECTED",
                unavailable.stream().map(String::valueOf).collect(Collectors.joining(",")),
                now,
                context.actor().subjectId());
        if (inserted != 1) {
            throw new IllegalStateException("reservation result raced after stock locks");
        }
        return new ReservationDecision(result, true);
    }

    private static InventoryReservationResult toResult(InventoryResultRow row) {
        if ("RESERVED".equals(row.getResultType())) {
            return new InventoryReserved(row.getCommandId(), row.getOrderId(), row.getDecidedAt());
        }
        List<Long> unavailable = Arrays.stream(row.getUnavailableProductIds().split(","))
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();
        return new InventoryRejected(
                row.getCommandId(), row.getOrderId(), unavailable, row.getDecidedAt());
    }
}
