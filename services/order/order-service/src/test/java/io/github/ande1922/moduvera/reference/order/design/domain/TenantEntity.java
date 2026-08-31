package io.github.ande1922.moduvera.reference.order.design.domain;

import io.github.ande1922.moduvera.context.TenantId;
import java.util.Objects;

public abstract class TenantEntity<ID> extends BaseEntity<ID> {

    private final TenantId tenantId;

    protected TenantEntity(ID id, TenantId tenantId, AuditMetadata audit) {
        super(id, audit);
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    }

    public final TenantId tenantId() {
        return tenantId;
    }
}
