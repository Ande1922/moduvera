package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryReservationHandler;
import io.github.ande1922.moduvera.reference.inventory.domain.AllOrNothingReservationPolicy;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationDecision;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationExecution;
import io.github.ande1922.moduvera.testing.messaging.InboundMessageContractProbe;
import io.github.ande1922.moduvera.testing.messaging.InboundMessageContractTck;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;

class ReserveInventoryCommandInboundConfigurationTest implements InboundMessageContractTck {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private final KafkaMessageMapper mapper = new KafkaMessageMapper();

    @Override
    public InboundMessageContractProbe newInboundMessageContractProbe() {
        var invocations = new AtomicInteger();
        var observedContext = new AtomicReference<ExecutionContext>();
        var accepted = new AtomicReference<ReserveInventoryCommand>();
        var handler = handler(command -> {
            invocations.incrementAndGet();
            observedContext.set(ExecutionContextHolder.require());
            accepted.set(command);
        });
        return new InboundMessageContractProbe(
                contract(),
                message(validPayload()),
                serialized -> deliver(handler, serialized),
                invocations::get,
                observedContext::get,
                () -> assertThat(accepted).hasValue(new ReserveInventoryCommand(
                        "reserve-order-42", 42, List.of(new ReserveInventoryLine(7, 2)))));
    }

    @Test
    void registersAStandardConsumerBoundToTheTypedApplicationHandler() {
        var handler = handler(ignored -> {});
        try (var context = context(handler)) {
            assertThat(context.getBean(InventoryReservationHandler.class))
                    .isInstanceOf(ApplicationMessageHandler.class);
            assertThat(context.getBean("reserveInventory")).isInstanceOf(Consumer.class);
        }
    }

    @Test
    void malformedPayloadIsRejectedBeforeTheApplicationHandler() {
        var invocations = new AtomicInteger();
        try (var context = context(handler(ignored -> invocations.incrementAndGet()))) {
            assertThatThrownBy(() -> consumer(context).accept(
                            mapper.toSpringMessage(message("{}"))))
                    .isInstanceOf(NonRetryableMessageException.class)
                    .hasMessage("invalid reserve inventory command");
        }
        assertThat(invocations).hasValue(0);
    }

    private void deliver(InventoryReservationHandler handler, SerializedMessage message) {
        try (var context = context(handler)) {
            consumer(context).accept(mapper.toSpringMessage(message));
        }
    }

    private AnnotationConfigApplicationContext context(InventoryReservationHandler handler) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(InventoryReservationHandler.class, () -> handler);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(ReliableMessageConsumerFactory.class, () -> new ReliableMessageConsumerFactory(mapper));
        context.register(ReserveInventoryCommandInboundConfiguration.class);
        context.refresh();
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Message<byte[]>> consumer(AnnotationConfigApplicationContext context) {
        return (Consumer<Message<byte[]>>) context.getBean("reserveInventory", Consumer.class);
    }

    private static InventoryReservationHandler handler(Consumer<ReserveInventoryCommand> behavior) {
        return new InventoryReservationHandler(
                new InboxTemplate("inventory-reservation", new AcceptingInbox(), new DirectTransactionBoundary(), fixedClock()),
                (request, now, policy) -> {
                    behavior.accept(request);
                    return new ReservationExecution(
                            request.commandId(), request.orderId(), ReservationDecision.reserved(), now, true);
                },
                new UseCaseAuthorizer(),
                fixedClock(),
                ignored -> {},
                new AllOrNothingReservationPolicy());
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static InboundMessageContract contract() {
        return new InboundMessageContract(
                MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:order-service"),
                new Destination(ReserveInventoryCommand.DESTINATION),
                new Actor(ActorType.SERVICE, "order-service", Set.of(InventoryReservationHandler.RESERVE.value())));
    }

    private static SerializedMessage message(String payload) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("reserve-message-42"),
                        MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                        new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:order-service"),
                        new Destination(ReserveInventoryCommand.DESTINATION),
                        NOW,
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "wire", Set.of("wire:permission")),
                        "corr-order",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        "42"),
                payload);
    }

    private static String validPayload() {
        return "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                + "\"lines\":[{\"productId\":7,\"quantity\":2}]}";
    }

    private static final class AcceptingInbox implements InboxRepository {
        @Override
        public boolean isProcessed(TenantId tenantId, String consumerId, MessageId messageId) { return false; }
        @Override
        public boolean tryStart(TenantId tenantId, String consumerId, MessageId messageId, Instant processedAt) { return true; }
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {
        @Override
        public <T> T inTransaction(Supplier<T> work) { return work.get(); }
    }
}
