package com.gaopc.platform.inventory.inbound.messaging;

import com.gaopc.platform.context.Actor;
import com.gaopc.platform.context.ActorType;
import com.gaopc.platform.inventory.api.InventoryApi;
import com.gaopc.platform.message.Destination;
import com.gaopc.platform.message.InboundMessageContract;
import com.gaopc.platform.message.MessageKind;
import com.gaopc.platform.message.MessageType;
import com.gaopc.platform.messaging.kafka.ReliableMessageConsumer;
import com.gaopc.platform.messaging.kafka.ReliableMessageConsumerFactory;
import java.net.URI;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ReserveInventoryCommandInboundConfiguration {

    @Bean
    ReserveInventoryCommandMessageHandler reserveInventoryCommandMessageHandler(
            InventoryApi inventory, ObjectMapper json) {
        return new ReserveInventoryCommandMessageHandler(inventory, json);
    }

    @Bean
    Consumer<Message<byte[]>> reserveInventory(
            ReliableMessageConsumerFactory consumers,
            ReserveInventoryCommandMessageHandler handler) {
        ReliableMessageConsumer consumer = consumers.forConsumer(
                "inventory-reservation",
                new InboundMessageContract(
                        MessageKind.ASYNC_COMMAND,
                        new MessageType("com.gaopc.inventory.reserve.v1"),
                        URI.create("urn:gaopc:order-service"),
                        new Destination("inventory.reserve"),
                        new Actor(
                                ActorType.SERVICE,
                                "order-service",
                                Set.of("inventory:reserve"))));
        return message -> consumer.handle(message, handler::handle);
    }
}
