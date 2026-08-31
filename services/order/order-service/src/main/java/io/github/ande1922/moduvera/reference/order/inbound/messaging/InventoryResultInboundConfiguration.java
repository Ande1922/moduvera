package io.github.ande1922.moduvera.reference.order.inbound.messaging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumer;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.order.application.InventoryResultHandler;
import java.net.URI;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class InventoryResultInboundConfiguration {

    @Bean
    Consumer<Message<byte[]>> inventoryResult(
            ReliableMessageConsumerFactory consumers,
            InventoryResultHandler handler,
            ObjectMapper json) {
        ReliableMessageConsumer consumer = consumers.forConsumer(
                "order-inventory-result",
                new InboundMessageContract(
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.reference.inventory.reservation-result.v1"),
                        URI.create("urn:moduvera:reference:inventory-service"),
                        new Destination("order.inventory-result"),
                        new Actor(
                                ActorType.SERVICE,
                                "inventory-service",
                                Set.of("order:apply-inventory-result"))));
        var mapper = new InventoryResultMessageMapper(json);
        return message -> consumer.handle(message, serialized -> handler.handle(mapper.map(serialized)));
    }
}
