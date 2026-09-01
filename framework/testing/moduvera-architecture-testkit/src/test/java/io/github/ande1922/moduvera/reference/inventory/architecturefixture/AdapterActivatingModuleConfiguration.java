package io.github.ande1922.moduvera.reference.inventory.architecturefixture;

import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.architecturefixture.OutboundDependency;

public final class AdapterActivatingModuleConfiguration {

    private final OutboundDependency adapter;

    public AdapterActivatingModuleConfiguration(OutboundDependency adapter) {
        this.adapter = adapter;
    }

    public OutboundDependency adapter() {
        return adapter;
    }
}
