package io.github.ande1922.moduvera.reference.order.design.persistence;

import java.time.Instant;

record OrderRow(
        String tenantId,
        long orderId,
        String status,
        long version,
        Instant createdAt,
        String createdByType,
        String createdById,
        Instant updatedAt,
        String updatedByType,
        String updatedById) {}
