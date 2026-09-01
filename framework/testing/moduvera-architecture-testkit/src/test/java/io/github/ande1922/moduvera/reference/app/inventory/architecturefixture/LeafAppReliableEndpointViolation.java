package io.github.ande1922.moduvera.reference.app.inventory.architecturefixture;

import io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint;

public final class LeafAppReliableEndpointViolation {

    private final ReliableInboundEndpoint endpoint;

    public LeafAppReliableEndpointViolation(ReliableInboundEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    public ReliableInboundEndpoint endpoint() {
        return endpoint;
    }
}
