package io.github.ande1922.moduvera.reference.order.design.domain;

import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import java.util.Objects;

public final class DemoOrder extends TenantEntity<Long> {

    private final OrderStatus status;
    private final AggregateVersion version;

    private DemoOrder(
            TenantId tenantId,
            long orderId,
            OrderStatus status,
            AggregateVersion version,
            AuditMetadata audit) {
        super(orderId, tenantId, audit);
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        this.status = Objects.requireNonNull(status, "status");
        this.version = Objects.requireNonNull(version, "version");
    }

    public static DemoOrder place(TenantId tenantId, long orderId) {
        return new DemoOrder(
                tenantId,
                orderId,
                OrderStatus.PENDING_STOCK,
                new AggregateVersion(0),
                AuditMetadata.unpersisted());
    }

    public static DemoOrder restore(
            TenantId tenantId,
            long orderId,
            OrderStatus status,
            AggregateVersion version,
            AuditMetadata audit) {
        if (!audit.persisted()) {
            throw new IllegalArgumentException("restored order requires persisted audit metadata");
        }
        return new DemoOrder(tenantId, orderId, status, version, audit);
    }

    public OrderStatus status() {
        return status;
    }

    public AggregateVersion version() {
        return version;
    }

    public DemoOrder confirm() {
        return new DemoOrder(tenantId(), id(), OrderStatus.CONFIRMED, version, audit());
    }

    public DemoOrder persisted(AggregateVersion storedVersion, AuditMetadata storedAudit) {
        return restore(tenantId(), id(), status, storedVersion, storedAudit);
    }
}
