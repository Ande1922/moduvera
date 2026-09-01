package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging;

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
import io.github.ande1922.moduvera.message.handler.CommandMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;

class ReserveInventoryCommandInboundConfigurationTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper();

    @Test
    void registersTheNamedCommandHandlerAndReliableSpringEndpoint() {
        try (var context = context(service(ignored -> {}))) {
            assertThat(context.getBean("reserveInventoryCommandMessageHandler"))
                    .isInstanceOf(CommandMessageHandler.class);
            assertThat(context.getBean("reserveInventory"))
                    .isInstanceOf(ReliableInboundEndpoint.class)
                    .isInstanceOf(Consumer.class);
        }
    }

    @Test
    void publicConsumerAcceptsTheCurrentInventoryV1Command() {
        var handled = new AtomicReference<ReserveInventoryCommand>();
        var service = service(command -> handled.set(command));

        try (var context = context(service)) {
            consumer(context).accept(mapper.toSpringMessage(message(validPayload())));
        }

        assertThat(handled.get()).isEqualTo(new ReserveInventoryCommand(
                "reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2))));
    }

    @Test
    void publicConsumerRejectsMalformedPayloadAsNonRetryable() {
        var invocations = new AtomicInteger();
        var service = service(command -> invocations.incrementAndGet());

        try (var context = context(service)) {
            assertThatThrownBy(() -> consumer(context)
                            .accept(mapper.toSpringMessage(message("{}"))))
                    .isInstanceOf(NonRetryableMessageException.class)
                    .hasMessage("invalid reserve inventory command");
        }

        assertThat(invocations.get()).isZero();
    }

    @Test
    void commandHandlerClassifiesMalformedPayloadAsNonRetryable() {
        try (var context = context(service(ignored -> {}))) {
            var handler = context.getBean(
                    "reserveInventoryCommandMessageHandler", CommandMessageHandler.class);

            assertThatThrownBy(() -> handler.handle(message("{}")))
                    .isInstanceOf(NonRetryableMessageException.class)
                    .hasMessage("invalid reserve inventory command");
        }
    }

    @Test
    void publicConsumerRejectsEveryMismatchedContractBeforeInventoryBehavior() {
        var invocations = new AtomicInteger();
        var service = service(command -> invocations.incrementAndGet());
        var valid = message(validPayload());
        var invalidMessages = List.of(
                withContract(valid, MessageKind.EVENT, valid.descriptor().type(),
                        valid.descriptor().source(), valid.descriptor().destination()),
                withContract(valid, MessageKind.ASYNC_COMMAND,
                        new MessageType("inventory.reserve.unknown.v1"),
                        valid.descriptor().source(), valid.descriptor().destination()),
                withContract(valid, MessageKind.ASYNC_COMMAND, valid.descriptor().type(),
                        URI.create("urn:moduvera:reference:unknown-service"),
                        valid.descriptor().destination()),
                withContract(valid, MessageKind.ASYNC_COMMAND, valid.descriptor().type(),
                        valid.descriptor().source(), new Destination("inventory.unknown")));

        try (var context = context(service)) {
            var consumer = consumer(context);
            invalidMessages.forEach(invalid -> assertThatThrownBy(
                            () -> consumer.accept(mapper.toSpringMessage(invalid)))
                    .isInstanceOf(NonRetryableMessageException.class)
                    .hasMessage("message does not match the expected inbound contract"));
        }

        assertThat(invocations.get()).isZero();
    }

    private AnnotationConfigApplicationContext context(InventoryApplicationService service) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(InventoryApplicationService.class, () -> service);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(ReliableMessageConsumerFactory.class, this::consumerFactory);
        context.registerBean(
                "anotherCommandMessageHandler",
                CommandMessageHandler.class,
                () -> ignored -> {});
        context.register(ReserveInventoryCommandInboundConfiguration.class);
        context.refresh();
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Message<byte[]>> consumer(AnnotationConfigApplicationContext context) {
        return (Consumer<Message<byte[]>>) context.getBean("reserveInventory", Consumer.class);
    }

    private static InventoryApplicationService service(Consumer<ReserveInventoryCommand> behavior) {
        return new InventoryApplicationService(
                (command, now) -> {
                    behavior.accept(command);
                    return new ReservationDecision(
                            new InventoryReserved(command.commandId(), command.orderId(), now), true);
                },
                new UseCaseAuthorizer(),
                Clock.systemUTC(),
                ignored -> {});
    }

    private ReliableMessageConsumerFactory consumerFactory() {
        InboxRepository inbox = (tenantId, consumerId, messageId, processedAt) -> true;
        return new ReliableMessageConsumerFactory(
                mapper,
                inbox,
                new DirectTransactionBoundary(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
    }

    private static SerializedMessage message(String payload) {
        return SerializedMessage.json(
                descriptor(
                        MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                        new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:order-service"),
                        new Destination(ReserveInventoryCommand.DESTINATION)),
                payload);
    }

    private static SerializedMessage withContract(
            SerializedMessage message,
            MessageKind kind,
            MessageType type,
            URI source,
            Destination destination) {
        return SerializedMessage.json(
                descriptor(kind, type, source, destination),
                new String(message.payload(), StandardCharsets.UTF_8));
    }

    private static MessageDescriptor descriptor(
            MessageKind kind, MessageType type, URI source, Destination destination) {
        return new MessageDescriptor(
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
                "42");
    }

    private static String validPayload() {
        return "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                + "\"lines\":[{\"productId\":7,\"quantity\":2}]}";
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
