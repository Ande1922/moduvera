package io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging;

import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ReserveInventoryPublicationConfiguration {

    @Bean
    ReserveInventoryPublisher reserveInventoryPublisher(
            DurablePublication outbox, ObjectMapper json, Clock clock) {
        return new OutboxReserveInventoryPublisher(outbox, json, clock);
    }
}
