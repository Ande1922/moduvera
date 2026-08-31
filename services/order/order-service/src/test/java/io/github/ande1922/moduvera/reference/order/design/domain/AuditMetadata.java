package io.github.ande1922.moduvera.reference.order.design.domain;

import java.time.Instant;
import java.util.Objects;

public record AuditMetadata(
        Instant createdAt, AuditActor createdBy, Instant updatedAt, AuditActor updatedBy) {

    public AuditMetadata {
        boolean completelyEmpty = createdAt == null && createdBy == null && updatedAt == null && updatedBy == null;
        if (!completelyEmpty) {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(createdBy, "createdBy");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(updatedBy, "updatedBy");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not precede createdAt");
            }
        }
    }

    public static AuditMetadata unpersisted() {
        return new AuditMetadata(null, null, null, null);
    }

    public static AuditMetadata created(AuditStamp stamp) {
        Objects.requireNonNull(stamp, "stamp");
        return new AuditMetadata(stamp.at(), stamp.actor(), stamp.at(), stamp.actor());
    }

    public boolean persisted() {
        return createdAt != null;
    }

    public AuditMetadata updated(AuditStamp stamp) {
        Objects.requireNonNull(stamp, "stamp");
        if (!persisted()) {
            return created(stamp);
        }
        return new AuditMetadata(createdAt, createdBy, stamp.at(), stamp.actor());
    }
}
