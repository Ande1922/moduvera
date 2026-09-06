package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryReservationHandler;
import java.net.URI;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ReserveInventoryCommandInboundConfiguration {

    @Bean
    Consumer<Message<byte[]>> reserveInventory(
            ReliableMessageConsumerFactory consumers,
            InventoryReservationHandler handler,
            ObjectMapper json) {
        var contract = new InboundMessageContract(
                MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:order-service"),
                new Destination(ReserveInventoryCommand.DESTINATION),
                new Actor(
                        ActorType.SERVICE,
                        "order-service",
                        Set.of(InventoryReservationHandler.RESERVE.value())));
        return consumers.forContract(contract, serialized -> {
            try {
                var command = json.readValue(serialized.payload(), ReserveInventoryCommand.class);
                handler.handle(command, serialized.descriptor().id());
            } catch (JacksonException invalid) {
                throw new NonRetryableMessageException("invalid reserve inventory command", invalid);
            }
        });
    }
}
