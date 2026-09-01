package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.http.architecturefixture;

import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.architecturefixture.OutboundDependency;

public final class InboundDependsOnOutboundViolation {

    private final OutboundDependency outbound;

    public InboundDependsOnOutboundViolation(OutboundDependency outbound) {
        this.outbound = outbound;
    }

    public OutboundDependency outbound() {
        return outbound;
    }
}
