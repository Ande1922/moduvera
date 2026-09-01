package io.github.ande1922.moduvera.reference.inventory.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
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
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.inbox.memory.InMemoryInboxRepository;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReserveInventoryCommandInboundConfigurationTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper();

    @Test
    void reserveConsumerAcceptsCurrentInventoryV1Identity() {
        var handled = new AtomicReference<ReserveInventoryCommand>();
        var service = new InventoryApplicationService(
                (command, now) -> {
                    handled.set(command);
                    return new ReservationDecision(
                            new InventoryReserved(
                                    command.commandId(), command.orderId(), Instant.EPOCH),
                            true);
                },
                new UseCaseAuthorizer(),
                Clock.systemUTC(),
                ignored -> {});
        var handler = new ReserveInventoryCommandMessageHandler(service, new ObjectMapper());
        var inbound = new ReserveInventoryCommandInboundConfiguration()
                .reserveInventory(consumerFactory(), handler);

        inbound.accept(mapper.toSpringMessage(message()));

        assertThat(handled.get()).isEqualTo(new ReserveInventoryCommand(
                "reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2))));
    }

    @Test
    void rejectsEveryMismatchedContractIdentityBeforeInvokingApplication() {
        var invocations = new AtomicInteger();
        var service = new InventoryApplicationService(
                (command, now) -> {
                    invocations.incrementAndGet();
                    return new ReservationDecision(
                            new InventoryReserved(command.commandId(), command.orderId(), now), true);
                },
                new UseCaseAuthorizer(),
                Clock.systemUTC(),
                ignored -> {});
        var handler = new ReserveInventoryCommandMessageHandler(service, new ObjectMapper());
        var inbound = new ReserveInventoryCommandInboundConfiguration()
                .reserveInventory(consumerFactory(), handler);
        var invalidMessages = List.of(
                message(
                        MessageKind.EVENT,
                        new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:order-service"),
                        new Destination(ReserveInventoryCommand.DESTINATION)),
                message(
                        MessageKind.ASYNC_COMMAND,
                        new MessageType("inventory.reserve.unknown.v1"),
                        URI.create("urn:moduvera:reference:order-service"),
                        new Destination(ReserveInventoryCommand.DESTINATION)),
                message(
                        MessageKind.ASYNC_COMMAND,
                        new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:unknown-service"),
                        new Destination(ReserveInventoryCommand.DESTINATION)),
                message(
                        MessageKind.ASYNC_COMMAND,
                        new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:order-service"),
                        new Destination("inventory.unknown")));

        invalidMessages.forEach(invalid -> assertThatThrownBy(
                        () -> inbound.accept(mapper.toSpringMessage(invalid)))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("message does not match the expected inbound contract"));

        assertThat(invocations.get()).isZero();
    }

    private ReliableMessageConsumerFactory consumerFactory() {
        return new ReliableMessageConsumerFactory(
                mapper,
                new InMemoryInboxRepository(),
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
    }

    private static SerializedMessage message() {
        return message(
                MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:order-service"),
                new Destination(ReserveInventoryCommand.DESTINATION));
    }

    private static SerializedMessage message(
            MessageKind kind, MessageType type, URI source, Destination destination) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("reserve-order-42"),
                        kind,
                        type,
                        source,
                        destination,
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
