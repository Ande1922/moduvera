package io.github.ande1922.moduvera.reference.order.design.domain;

import java.util.Objects;

public abstract class BaseEntity<ID> {

    private final ID id;
    private final AuditMetadata audit;

    protected BaseEntity(ID id, AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public final ID id() {
        return id;
    }

    public final AuditMetadata audit() {
        return audit;
    }
}
