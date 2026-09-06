package io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging;

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
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.application.InventoryResultHandler;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderLine;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import io.github.ande1922.moduvera.testing.messaging.InboundMessageContractProbe;
import io.github.ande1922.moduvera.testing.messaging.InboundMessageContractTck;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;

class InventoryResultInboundConfigurationTest implements InboundMessageContractTck {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:01Z");
    private final KafkaMessageMapper mapper = new KafkaMessageMapper();

    @Override
    public InboundMessageContractProbe newInboundMessageContractProbe() {
        var repository = new RecordingOrderRepository(pendingOrder());
        return new InboundMessageContractProbe(
                contract(),
                message("contract-reserved", reservedPayload()),
                serialized -> deliver(repository, serialized),
                repository.finds::get,
                repository.observedContext::get,
                () -> {
                    assertThat(repository.order.status()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(repository.saves).hasValue(1);
                });
    }

    @Test
    void registersAStandardConsumerBoundToOneCombinedApplicationHandler() {
        try (var context = context(new RecordingOrderRepository(pendingOrder()))) {
            assertThat(context.getBean(InventoryResultHandler.class))
                    .isInstanceOf(ApplicationMessageHandler.class);
            assertThat(context.getBean("inventoryResult")).isInstanceOf(Consumer.class);
        }
    }

    @Test
    void resolvesReservedAndRejectedPayloadsThroughThePublicConsumer() {
        var reserved = new RecordingOrderRepository(pendingOrder());
        deliver(reserved, message("reserved", reservedPayload()));
        assertThat(reserved.order.status()).isEqualTo(OrderStatus.CONFIRMED);

        var rejected = new RecordingOrderRepository(pendingOrder());
        deliver(rejected, message("rejected", rejectedPayload()));
        assertThat(rejected.order.status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void malformedPayloadIsRejectedBeforeApplicationWork() {
        var repository = new RecordingOrderRepository(pendingOrder());
        assertThatThrownBy(() -> deliver(repository, message("malformed", "{}")))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasMessage("inventory result is missing commandId");
        assertThat(repository.finds).hasValue(0);
    }

    private void deliver(RecordingOrderRepository repository, SerializedMessage serialized) {
        try (var context = context(repository)) {
            consumer(context).accept(mapper.toSpringMessage(serialized));
        }
    }

    private AnnotationConfigApplicationContext context(RecordingOrderRepository repository) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(InventoryResultHandler.class, () -> new InventoryResultHandler(
                new InboxTemplate("order-inventory-result", new AcceptingInbox(), new DirectTransactionBoundary(), fixedClock()),
                repository,
                new UseCaseAuthorizer()));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(ReliableMessageConsumerFactory.class, () -> new ReliableMessageConsumerFactory(mapper));
        context.register(InventoryResultInboundConfiguration.class);
        context.refresh();
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Message<byte[]>> consumer(AnnotationConfigApplicationContext context) {
        return (Consumer<Message<byte[]>>) context.getBean("inventoryResult", Consumer.class);
    }

    private static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    private static InboundMessageContract contract() {
        return new InboundMessageContract(
                MessageKind.valueOf(InventoryReservationResult.MESSAGE_KIND),
                new MessageType(InventoryReservationResult.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:inventory-service"),
                new Destination(InventoryReservationResult.DESTINATION),
                new Actor(ActorType.SERVICE, "inventory-service", Set.of(InventoryResultHandler.APPLY_INVENTORY_RESULT.value())));
    }

    private static SerializedMessage message(String id, String payload) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        MessageKind.valueOf(InventoryReservationResult.MESSAGE_KIND),
                        new MessageType(InventoryReservationResult.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:inventory-service"),
                        new Destination(InventoryReservationResult.DESTINATION),
                        NOW,
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "wire", Set.of("wire:permission")),
                        "corr-order",
                        new MessageId("reserve-order-42"),
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:42"),
                payload);
    }

    private static String reservedPayload() {
        return "{\"commandId\":\"reserve-order-42\",\"orderId\":42,\"reservedAt\":\"2026-08-30T00:00:01Z\"}";
    }

    private static String rejectedPayload() {
        return "{\"commandId\":\"reserve-order-42\",\"orderId\":42,\"unavailableProductIds\":[7],\"rejectedAt\":\"2026-08-30T00:00:01Z\"}";
    }

    private static Order pendingOrder() {
        return Order.place(42, List.of(new OrderLine(7, "Coffee", 2, new BigDecimal("18.00"))), Currency.getInstance("CNY"), NOW);
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

    private static final class RecordingOrderRepository implements OrderRepository {
        private final Order order;
        private final AtomicInteger finds = new AtomicInteger();
        private final AtomicInteger saves = new AtomicInteger();
        private final AtomicReference<ExecutionContext> observedContext = new AtomicReference<>();

        private RecordingOrderRepository(Order order) { this.order = order; }
        @Override
        public Optional<Order> findById(long orderId) {
            finds.incrementAndGet();
            observedContext.set(ExecutionContextHolder.require());
            return this.order.id() == orderId ? Optional.of(this.order) : Optional.empty();
        }
        @Override
        public void save(Order order) { saves.incrementAndGet(); }
    }
}
