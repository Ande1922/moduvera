package io.github.ande1922.moduvera.reference.inventory.inbound.messaging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryApi;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumer;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
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
                        MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                        new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:order-service"),
                        new Destination(ReserveInventoryCommand.DESTINATION),
                        new Actor(
                                ActorType.SERVICE,
                                "order-service",
                                Set.of("inventory:reserve"))));
        return message -> consumer.handle(message, handler::handle);
    }
}
