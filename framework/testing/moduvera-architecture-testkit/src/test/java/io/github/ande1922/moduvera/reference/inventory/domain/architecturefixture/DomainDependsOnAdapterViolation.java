package io.github.ande1922.moduvera.reference.inventory.domain.architecturefixture;

import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.architecturefixture.OutboundDependency;

public final class DomainDependsOnAdapterViolation {

    private final OutboundDependency outbound;

    public DomainDependsOnAdapterViolation(OutboundDependency outbound) {
        this.outbound = outbound;
    }

    public OutboundDependency outbound() {
        return outbound;
    }
}
