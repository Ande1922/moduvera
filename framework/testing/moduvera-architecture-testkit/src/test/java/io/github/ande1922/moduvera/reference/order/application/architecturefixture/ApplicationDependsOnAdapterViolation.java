package io.github.ande1922.moduvera.reference.order.application.architecturefixture;

import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.architecturefixture.OutboundDependency;

public final class ApplicationDependsOnAdapterViolation {

    private final OutboundDependency outbound;

    public ApplicationDependsOnAdapterViolation(OutboundDependency outbound) {
        this.outbound = outbound;
    }

    public OutboundDependency outbound() {
        return outbound;
    }
}
