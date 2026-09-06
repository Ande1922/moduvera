package io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import io.github.ande1922.moduvera.message.handler.EventMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.application.OrderApplicationService;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
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
import java.util.concurrent.atomic.AtomicLong;
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
        var contract = inventoryResultContract();
        var validMessage = message(
                "contract-result-reserved",
                "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                        + "\"reservedAt\":\"2026-08-30T00:00:01Z\"}");
        return new InboundMessageContractProbe(
                contract,
                validMessage,
                serialized -> deliver(repository, serialized),
                repository::finds,
                repository::observedContext,
                () -> {
                    assertThat(repository.requestedOrderId()).isEqualTo(42);
                    assertThat(repository.order().status()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(repository.saves()).isEqualTo(1);
                });
    }

    @Test
    void registersTheNamedEventHandlerAndReliableSpringEndpoint() {
        try (var context = context(new RecordingOrderRepository(pendingOrder()))) {
            assertThat(context.getBean("inventoryResultMessageHandler"))
                    .isInstanceOf(EventMessageHandler.class);
            assertThat(context.getBean("inventoryResult"))
                    .isInstanceOf(ReliableInboundEndpoint.class)
                    .isInstanceOf(Consumer.class);
        }
    }

    @Test
    void resolvesReservedAndRejectedResultsThroughThePublicConsumer() {
        var reserved = new RecordingOrderRepository(pendingOrder());
        try (var context = context(reserved)) {
            consumer(context).accept(mapper.toSpringMessage(message(
                    "result-reserved",
                    "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                            + "\"reservedAt\":\"2026-08-30T00:00:01Z\"}")));
        }
        assertThat(reserved.order().status()).isEqualTo(OrderStatus.CONFIRMED);

        var rejected = new RecordingOrderRepository(pendingOrder());
        try (var context = context(rejected)) {
            consumer(context).accept(mapper.toSpringMessage(message(
                    "result-rejected",
                    "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                            + "\"unavailableProductIds\":[7],"
                            + "\"rejectedAt\":\"2026-08-30T00:00:01Z\"}")));
        }
        assertThat(rejected.order().status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void acceptsRepeatedTerminalResultAndRejectsAConflictingTerminalResult() {
        var repository = new RecordingOrderRepository(pendingOrder());
        try (var context = context(repository)) {
            var consumer = consumer(context);
            var reserved = "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                    + "\"reservedAt\":\"2026-08-30T00:00:01Z\"}";

            consumer.accept(mapper.toSpringMessage(message("result-reserved-1", reserved)));
            assertThatCode(() -> consumer.accept(
                            mapper.toSpringMessage(message("result-reserved-2", reserved))))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> consumer.accept(mapper.toSpringMessage(message(
                            "result-rejected",
                            "{\"commandId\":\"reserve-order-42\",\"orderId\":42,"
                                    + "\"unavailableProductIds\":[7],"
                                    + "\"rejectedAt\":\"2026-08-30T00:00:01Z\"}"))))
                    .isInstanceOf(NonRetryableMessageException.class)
                    .hasMessage("message handling exhausted 1 attempts")
                    .hasCauseInstanceOf(IllegalStateException.class);
        }

        assertThat(repository.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(repository.saves()).isEqualTo(2);
    }

    private AnnotationConfigApplicationContext context(RecordingOrderRepository repository) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(OrderApplicationService.class, () -> service(repository));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(ReliableMessageConsumerFactory.class, this::consumerFactory);
        context.register(InventoryResultInboundConfiguration.class);
        context.refresh();
        return context;
    }

    private void deliver(RecordingOrderRepository repository, SerializedMessage serialized) {
        try (var context = context(repository)) {
            consumer(context).accept(mapper.toSpringMessage(serialized));
        }
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Message<byte[]>> consumer(AnnotationConfigApplicationContext context) {
        return (Consumer<Message<byte[]>>) context.getBean("inventoryResult", Consumer.class);
    }

    private ReliableMessageConsumerFactory consumerFactory() {
        InboxRepository inbox = new InboxRepository() {
            @Override
            public boolean isProcessed(
                    TenantId tenantId, String consumerId, MessageId messageId) {
                return false;
            }

            @Override
            public boolean tryStart(
                    TenantId tenantId,
                    String consumerId,
                    MessageId messageId,
                    Instant processedAt) {
                return true;
            }
        };
        return new ReliableMessageConsumerFactory(
                mapper,
                inbox,
                new DirectTransactionBoundary(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OrderApplicationService service(OrderRepository repository) {
        CatalogApi catalog = query -> new ProductSnapshot(
                query.productId(), "Coffee", new BigDecimal("18.00"), Currency.getInstance("CNY"), 1);
        return new OrderApplicationService(
                catalog,
                repository,
                (ReserveInventoryPublisher) ignored -> {},
                () -> 42,
                new DirectTransactionBoundary(),
                new UseCaseAuthorizer(),
                Clock.systemUTC());
    }

    private static Order pendingOrder() {
        return Order.place(
                42,
                List.of(new OrderLine(7, "Coffee", 2, new BigDecimal("18.00"))),
                Currency.getInstance("CNY"),
                NOW);
    }

    private static InboundMessageContract inventoryResultContract() {
        return new InboundMessageContract(
                MessageKind.valueOf(InventoryReservationResult.MESSAGE_KIND),
                new MessageType(InventoryReservationResult.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:inventory-service"),
                new Destination(InventoryReservationResult.DESTINATION),
                new Actor(
                        ActorType.SERVICE,
                        "inventory-service",
                        Set.of("order:apply-inventory-result")));
    }

    private static SerializedMessage message(String messageId, String payload) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(messageId),
                        MessageKind.valueOf(InventoryReservationResult.MESSAGE_KIND),
                        new MessageType(InventoryReservationResult.MESSAGE_TYPE),
                        URI.create("urn:moduvera:reference:inventory-service"),
                        new Destination(InventoryReservationResult.DESTINATION),
                        NOW,
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "inventory-service"),
                        "corr-order",
                        new MessageId("reserve-order-42"),
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:42"),
                payload);
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }

    private static final class RecordingOrderRepository implements OrderRepository {

        private final Order order;
        private final AtomicInteger saves = new AtomicInteger();
        private final AtomicInteger finds = new AtomicInteger();
        private final AtomicLong requestedOrderId = new AtomicLong();
        private final AtomicReference<ExecutionContext> observedContext = new AtomicReference<>();

        private RecordingOrderRepository(Order order) {
            this.order = order;
        }

        @Override
        public Optional<Order> findById(long orderId) {
            finds.incrementAndGet();
            requestedOrderId.set(orderId);
            observedContext.set(ExecutionContextHolder.require());
            return order.id() == orderId ? Optional.of(order) : Optional.empty();
        }

        @Override
        public void save(Order order) {
            saves.incrementAndGet();
        }

        private Order order() {
            return order;
        }

        private int saves() {
            return saves.get();
        }

        private int finds() {
            return finds.get();
        }

        private long requestedOrderId() {
            return requestedOrderId.get();
        }

        private ExecutionContext observedContext() {
            return observedContext.get();
        }
    }
}
