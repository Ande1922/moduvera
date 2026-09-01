package io.github.ande1922.moduvera.reference.inventory.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.inbox.memory.InMemoryInboxRepository;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReserveInventoryCommandInboundConfigurationTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper();

    @Test
    void reserveConsumerAcceptsCurrentInventoryV1Identity() {
        var handled = new AtomicReference<ReserveInventoryCommand>();
        var handler = new ReserveInventoryCommandMessageHandler(
                command -> {
                    handled.set(command);
                    return new InventoryReserved(
                            command.commandId(), command.orderId(), Instant.EPOCH);
                },
                new ObjectMapper());
        var inbound = new ReserveInventoryCommandInboundConfiguration()
                .reserveInventory(consumerFactory(), handler);

        inbound.accept(mapper.toSpringMessage(message()));

        assertThat(handled.get()).isEqualTo(new ReserveInventoryCommand(
                "reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2))));
    }

    private ReliableMessageConsumerFactory consumerFactory() {
        return new ReliableMessageConsumerFactory(
                mapper,
                new InMemoryInboxRepository(),
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
    }

    private static SerializedMessage message() {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("reserve-order-42"),
                        MessageKind.ASYNC_COMMAND,
                        new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                        URI.create("urn:moduvera:reference:order-service"),
                        new Destination("inventory.reserve"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "order-service"),
                        "corr-order",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        "42"),
                "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                        + "\"lines\":[{\"productId\":7,\"quantity\":2}]}");
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
