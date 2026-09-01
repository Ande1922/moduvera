package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.handler.CommandMessageHandler;
import io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ReserveInventoryCommandInboundConfiguration {

    static final String HANDLER_BEAN = "reserveInventoryCommandMessageHandler";

    @Bean(HANDLER_BEAN)
    CommandMessageHandler reserveInventoryCommandMessageHandler(
            InventoryApplicationService inventory,
            ObjectMapper json) {
        return new ReserveInventoryCommandMessageHandler(inventory, json);
    }

    @Bean
    ReliableInboundEndpoint reserveInventory(
            ReliableMessageConsumerFactory consumers,
            @Qualifier(HANDLER_BEAN) CommandMessageHandler handler) {
        var contract = new InboundMessageContract(
                MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:order-service"),
                new Destination(ReserveInventoryCommand.DESTINATION),
                new Actor(
                        ActorType.SERVICE,
                        "order-service",
                        Set.of(InventoryApplicationService.RESERVE.value())));
        return consumers.forConsumer("inventory-reservation", contract, handler);
    }
}
