package io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderLine;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import java.time.Clock;
import java.util.Currency;
import java.util.Optional;

public final class MybatisOrderRepository implements OrderRepository {

    private final OrderMapper mapper;
    private final Clock clock;

    public MybatisOrderRepository(OrderMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Optional<Order> findById(long orderId) {
        String tenantId = ExecutionContextHolder.require().tenantId().value();
        OrderHeaderRow header = mapper.findHeader(tenantId, orderId);
        if (header == null) {
            return Optional.empty();
        }
        var lines = mapper.findLines(tenantId, orderId).stream()
                .map(row -> new OrderLine(
                        row.getProductId(), row.getProductName(), row.getQuantity(), row.getUnitPrice()))
                .toList();
        return Optional.of(Order.restore(
                header.getOrderId(),
                lines,
                Currency.getInstance(header.getCurrency()),
                header.getPlacedAt(),
                OrderStatus.valueOf(header.getStatus()),
                header.getVersion()));
    }

    @Override
    public void save(Order order) {
        var context = ExecutionContextHolder.require();
        String tenantId = context.tenantId().value();
        String actor = context.actor().subjectId();
        var now = clock.instant();
        if (!order.isPersisted()) {
            mapper.insertHeader(
                    tenantId,
                    order.id(),
                    order.status().name(),
                    order.currency().getCurrencyCode(),
                    order.placedAt(),
                    order.version(),
                    now,
                    actor);
            for (int index = 0; index < order.lines().size(); index++) {
                OrderLine line = order.lines().get(index);
                var row = new OrderLineRow();
                row.setTenantId(tenantId);
                row.setOrderId(order.id());
                row.setLineNumber(index + 1);
                row.setProductId(line.productId());
                row.setProductName(line.productName());
                row.setQuantity(line.quantity());
                row.setUnitPrice(line.unitPrice());
                mapper.insertLine(row);
            }
            order.markPersisted(order.version());
            return;
        }
        int updated = mapper.updateStatus(
                tenantId,
                order.id(),
                order.status().name(),
                order.version(),
                now,
                actor);
        if (updated != 1) {
            throw new OrderConcurrentModificationException(order.id());
        }
        order.markPersisted(order.version() + 1);
    }
}
