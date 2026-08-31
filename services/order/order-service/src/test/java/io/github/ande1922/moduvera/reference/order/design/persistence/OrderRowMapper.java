package io.github.ande1922.moduvera.reference.order.design.persistence;

import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.design.domain.AggregateVersion;
import io.github.ande1922.moduvera.reference.order.design.domain.AuditActor;
import io.github.ande1922.moduvera.reference.order.design.domain.AuditMetadata;
import io.github.ande1922.moduvera.reference.order.design.domain.DemoOrder;

final class OrderRowMapper {

    OrderRow toRow(DemoOrder order) {
        AuditMetadata audit = order.audit();
        if (!audit.persisted()) {
            throw new IllegalArgumentException("cannot map unpersisted audit metadata to a row");
        }
        return new OrderRow(
                order.tenantId().value(),
                order.id(),
                order.status().name(),
                order.version().value(),
                audit.createdAt(),
                audit.createdBy().type().name(),
                audit.createdBy().subjectId(),
                audit.updatedAt(),
                audit.updatedBy().type().name(),
                audit.updatedBy().subjectId());
    }

    DemoOrder toDomain(OrderRow row) {
        AuditMetadata audit = new AuditMetadata(
                row.createdAt(),
                new AuditActor(ActorType.valueOf(row.createdByType()), row.createdById()),
                row.updatedAt(),
                new AuditActor(ActorType.valueOf(row.updatedByType()), row.updatedById()));
        return DemoOrder.restore(
                new TenantId(row.tenantId()),
                row.orderId(),
                OrderStatus.valueOf(row.status()),
                new AggregateVersion(row.version()),
                audit);
    }
}
