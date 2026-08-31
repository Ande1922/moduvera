package io.github.ande1922.moduvera.reference.order.domain;

import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.api.OrderView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

public final class Order {

    private final long id;
    private final List<OrderLine> lines;
    private final Currency currency;
    private final Instant placedAt;
    private OrderStatus status;
    private long version;
    private boolean persisted;

    private Order(
            long id,
            List<OrderLine> lines,
            Currency currency,
            Instant placedAt,
            OrderStatus status,
            long version,
            boolean persisted) {
        this.id = id;
        this.lines = List.copyOf(lines);
        this.currency = currency;
        this.placedAt = placedAt;
        this.status = status;
        this.version = version;
        this.persisted = persisted;
    }

    public static Order place(long id, List<OrderLine> lines, Currency currency, Instant placedAt) {
        if (id <= 0 || lines.isEmpty() || currency == null || placedAt == null) {
            throw new IllegalArgumentException("order requires identity, lines, currency and business time");
        }
        return new Order(id, lines, currency, placedAt, OrderStatus.PENDING_STOCK, 0, false);
    }

    public static Order restore(
            long id,
            List<OrderLine> lines,
            Currency currency,
            Instant placedAt,
            OrderStatus status,
            long version) {
        if (id <= 0
                || lines.isEmpty()
                || currency == null
                || placedAt == null
                || status == null
                || version < 0) {
            throw new IllegalArgumentException("persisted order state is invalid");
        }
        return new Order(id, lines, currency, placedAt, status, version, true);
    }

    public long id() {
        return id;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public Currency currency() {
        return currency;
    }

    public Instant placedAt() {
        return placedAt;
    }

    public OrderStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public boolean isPersisted() {
        return persisted;
    }

    public void markPersisted(long persistedVersion) {
        if (persistedVersion < version || persistedVersion > version + 1) {
            throw new IllegalArgumentException("persisted version must preserve optimistic ordering");
        }
        version = persistedVersion;
        persisted = true;
    }

    public void apply(InventoryReserved result) {
        requireMatchingOrder(result.orderId());
        if (status == OrderStatus.PENDING_STOCK) {
            status = OrderStatus.CONFIRMED;
        } else if (status != OrderStatus.CONFIRMED) {
            throw new IllegalStateException("reserved result conflicts with terminal order status");
        }
    }

    public void apply(InventoryRejected result) {
        requireMatchingOrder(result.orderId());
        if (status == OrderStatus.PENDING_STOCK) {
            status = OrderStatus.REJECTED;
        } else if (status != OrderStatus.REJECTED) {
            throw new IllegalStateException("rejected result conflicts with terminal order status");
        }
    }

    public OrderView toView() {
        BigDecimal total = lines.stream().map(OrderLine::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderView(id, status, lines.stream().map(OrderLine::toView).toList(), total, currency, placedAt);
    }

    private void requireMatchingOrder(long resultOrderId) {
        if (id != resultOrderId) {
            throw new IllegalArgumentException("inventory result belongs to another order");
        }
    }
}
