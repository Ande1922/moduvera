package io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.handler.EventMessageHandler;
import io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.order.application.OrderApplicationService;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class InventoryResultInboundConfiguration {

    static final String HANDLER_BEAN = "inventoryResultMessageHandler";

    @Bean(HANDLER_BEAN)
    EventMessageHandler inventoryResultMessageHandler(
            OrderApplicationService orders, ObjectMapper json) {
        return new InventoryResultMessageHandler(orders, new InventoryResultMessageMapper(json));
    }

    @Bean
    ReliableInboundEndpoint inventoryResult(
            ReliableMessageConsumerFactory consumers,
            @Qualifier(HANDLER_BEAN) EventMessageHandler handler) {
        return consumers.forConsumer(
                "order-inventory-result",
                new InboundMessageContract(
                        MessageKind.valueOf(InventoryReservationResult.MESSAGE_KIND),
                        new MessageType(InventoryReservationResult.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:inventory-service"),
                        new Destination(InventoryReservationResult.DESTINATION),
                        new Actor(
                                ActorType.SERVICE,
                                "inventory-service",
                                Set.of("order:apply-inventory-result"))),
                handler);
    }
}
