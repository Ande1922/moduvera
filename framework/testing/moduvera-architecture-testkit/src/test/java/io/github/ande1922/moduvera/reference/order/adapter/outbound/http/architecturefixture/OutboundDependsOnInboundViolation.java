package io.github.ande1922.moduvera.reference.order.adapter.outbound.http.architecturefixture;

import io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging.architecturefixture.InboundDependency;

public final class OutboundDependsOnInboundViolation {

    private final InboundDependency inbound;

    public OutboundDependsOnInboundViolation(InboundDependency inbound) {
        this.inbound = inbound;
    }

    public InboundDependency inbound() {
        return inbound;
    }
}
