package io.github.ande1922.moduvera.reference.app.inventory.architecturefixture;

import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;

public final class LeafAppReliableConsumerFactoryViolation {

    private final ReliableMessageConsumerFactory consumers;

    public LeafAppReliableConsumerFactoryViolation(ReliableMessageConsumerFactory consumers) {
        this.consumers = consumers;
    }

    public ReliableMessageConsumerFactory consumers() {
        return consumers;
    }
}
