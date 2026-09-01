package io.github.ande1922.moduvera.reference.order.inbound.messaging;

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
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InventoryResultInboundConfigurationTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper();

    @Test
    void resultConsumerAcceptsCurrentInventoryV1Identity() {
        var handled = new AtomicReference<InventoryReservationResult>();
        var inbound = new InventoryResultInboundConfiguration()
                .inventoryResult(consumerFactory(), handled::set, new ObjectMapper());

        inbound.accept(mapper.toSpringMessage(message()));

        assertThat(handled.get()).isEqualTo(new InventoryReserved(
                "reserve-order-42", 42, Instant.parse("2026-08-30T00:00:01Z")));
    }

    private ReliableMessageConsumerFactory consumerFactory() {
        return new ReliableMessageConsumerFactory(
                mapper,
                new InMemoryInboxRepository(),
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:01Z"), ZoneOffset.UTC));
    }

    private static SerializedMessage message() {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("inventory-result:reserve-order-42"),
                        MessageKind.EVENT,
                        new MessageType(
                                "io.github.ande1922.moduvera.reference.inventory.reservation-result.v1"),
                        URI.create("urn:moduvera:reference:inventory-service"),
                        new Destination("order.inventory-result"),
                        Instant.parse("2026-08-30T00:00:01Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "inventory-service"),
                        "corr-order",
                        new MessageId("reserve-order-42"),
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:42"),
                "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                        + "\"reservedAt\":\"2026-08-30T00:00:01Z\"}");
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
