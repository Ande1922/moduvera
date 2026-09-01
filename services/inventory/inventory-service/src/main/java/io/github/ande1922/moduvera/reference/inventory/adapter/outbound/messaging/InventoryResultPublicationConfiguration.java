package io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging;

import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class InventoryResultPublicationConfiguration {

    @Bean
    InventoryResultPublisher inventoryResultPublisher(
            DurablePublication outbox, ObjectMapper json, Clock clock) {
        return new OutboxInventoryResultPublisher(outbox, json, clock);
    }
}
