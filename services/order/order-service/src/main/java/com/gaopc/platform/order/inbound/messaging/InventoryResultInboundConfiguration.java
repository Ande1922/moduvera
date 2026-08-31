package com.gaopc.platform.order.inbound.messaging;

import com.gaopc.platform.context.Actor;
import com.gaopc.platform.context.ActorType;
import com.gaopc.platform.message.Destination;
import com.gaopc.platform.message.InboundMessageContract;
import com.gaopc.platform.message.MessageKind;
import com.gaopc.platform.message.MessageType;
import com.gaopc.platform.messaging.kafka.ReliableMessageConsumer;
import com.gaopc.platform.messaging.kafka.ReliableMessageConsumerFactory;
import com.gaopc.platform.order.application.InventoryResultHandler;
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
                        new MessageType("com.gaopc.inventory.reservation-result.v1"),
                        URI.create("urn:gaopc:inventory-service"),
                        new Destination("order.inventory-result"),
                        new Actor(
                                ActorType.SERVICE,
                                "inventory-service",
                                Set.of("order:apply-inventory-result"))));
        var mapper = new InventoryResultMessageMapper(json);
        return message -> consumer.handle(message, serialized -> handler.handle(mapper.map(serialized)));
    }
}
