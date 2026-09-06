package io.github.ande1922.moduvera.reference.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.authorization.PermissionDeniedException;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryRejected;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderLine;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InventoryResultHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:01Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MessageId MESSAGE_ID = new MessageId("inventory-result-42");

    @Test
    void committedDeliverySkipsAuthorizationAndMutableOrderRead() {
        var inbox = new RecordingInboxRepository(true);
        var orders = new RecordingOrderRepository(pendingOrder());
        var handler = handler(inbox, orders);

        ExecutionContextHolder.run(context(Set.of()), () -> handler.handle(reserved(), MESSAGE_ID));

        assertThat(inbox.starts).isZero();
        assertThat(orders.finds).isZero();
        assertThat(orders.saves).isZero();
    }

    @Test
    void authorizationRunsBeforeTheInboxTransactionAndMutableOrderRead() {
        var inbox = new RecordingInboxRepository(false);
        var orders = new RecordingOrderRepository(pendingOrder());
        var handler = handler(inbox, orders);

        assertThatThrownBy(() -> ExecutionContextHolder.run(
                        context(Set.of()), () -> handler.handle(reserved(), MESSAGE_ID)))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining(InventoryResultHandler.APPLY_INVENTORY_RESULT.value());
        assertThat(inbox.starts).isZero();
        assertThat(orders.finds).isZero();
        assertThat(orders.saves).isZero();
    }

    @Test
    void reservedAndRejectedResultsApplyInsideTheInboxTransaction() {
        var reservedTransaction = new RecordingTransactionBoundary();
        var reservedOrders = new RecordingOrderRepository(pendingOrder(), reservedTransaction);
        var reservedInbox = new RecordingInboxRepository(false);
        ExecutionContextHolder.run(
                allowedContext(),
                () -> handler(reservedInbox, reservedOrders, reservedTransaction)
                        .handle(reserved(), MESSAGE_ID));

        assertThat(reservedInbox.starts).isOne();
        assertThat(reservedTransaction.calls).isOne();
        assertThat(reservedTransaction.active).isFalse();
        assertThat(reservedOrders.finds).isOne();
        assertThat(reservedOrders.saves).isOne();
        assertThat(reservedOrders.order.status()).isEqualTo(OrderStatus.CONFIRMED);

        var rejectedTransaction = new RecordingTransactionBoundary();
        var rejectedOrders = new RecordingOrderRepository(pendingOrder(), rejectedTransaction);
        var rejectedInbox = new RecordingInboxRepository(false);
        var rejected = new InventoryRejected("reserve-order-42", 42, List.of(7L), NOW);
        ExecutionContextHolder.run(
                allowedContext(),
                () -> handler(rejectedInbox, rejectedOrders, rejectedTransaction)
                        .handle(rejected, new MessageId("inventory-result-43")));

        assertThat(rejectedInbox.starts).isOne();
        assertThat(rejectedOrders.finds).isOne();
        assertThat(rejectedOrders.saves).isOne();
        assertThat(rejectedOrders.order.status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void repeatedTerminalResultRemainsIdempotentAndAConflictingResultStillFails() {
        var orders = new RecordingOrderRepository(pendingOrder());
        var handler = handler(new RecordingInboxRepository(false), orders);

        ExecutionContextHolder.run(allowedContext(), () -> {
            handler.handle(reserved(), new MessageId("inventory-result-1"));
            handler.handle(reserved(), new MessageId("inventory-result-2"));
            assertThatThrownBy(() -> handler.handle(
                            new InventoryRejected("reserve-order-42", 42, List.of(7L), NOW),
                            new MessageId("inventory-result-3")))
                    .isInstanceOf(IllegalStateException.class);
        });

        assertThat(orders.order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.saves).isEqualTo(2);
    }

    private static InventoryResultHandler handler(
            InboxRepository inbox, OrderRepository orders) {
        return handler(inbox, orders, new RecordingTransactionBoundary());
    }

    private static InventoryResultHandler handler(
            InboxRepository inbox,
            OrderRepository orders,
            TransactionBoundary transactions) {
        return new InventoryResultHandler(
                new InboxTemplate(
                        "order-inventory-result",
                        inbox,
                        transactions,
                        CLOCK),
                orders,
                new UseCaseAuthorizer());
    }

    private static InventoryReserved reserved() {
        return new InventoryReserved("reserve-order-42", 42, NOW);
    }

    private static ExecutionContext allowedContext() {
        return context(Set.of(InventoryResultHandler.APPLY_INVENTORY_RESULT.value()));
    }

    private static ExecutionContext context(Set<String> permissions) {
        return ExecutionContext.initiatedBy(
                new TenantId("tenant-a"),
                new Actor(ActorType.SERVICE, "inventory-service", permissions),
                "corr-order");
    }

    private static Order pendingOrder() {
        return Order.place(
                42,
                List.of(new OrderLine(
                        7,
                        "Coffee",
                        1,
                        new BigDecimal("18.00"))),
                Currency.getInstance("CNY"),
                NOW.minusSeconds(1));
    }

    private static final class RecordingInboxRepository implements InboxRepository {
        private final boolean processed;
        private int starts;

        private RecordingInboxRepository(boolean processed) {
            this.processed = processed;
        }

        @Override
        public boolean isProcessed(TenantId tenantId, String consumerId, MessageId messageId) {
            return processed;
        }

        @Override
        public boolean tryStart(
                TenantId tenantId,
                String consumerId,
                MessageId messageId,
                Instant processedAt) {
            starts++;
            return true;
        }
    }

    private static final class RecordingOrderRepository implements OrderRepository {
        private final Order order;
        private final RecordingTransactionBoundary transaction;
        private int finds;
        private int saves;

        private RecordingOrderRepository(Order order) {
            this(order, null);
        }

        private RecordingOrderRepository(
                Order order, RecordingTransactionBoundary transaction) {
            this.order = order;
            this.transaction = transaction;
        }

        @Override
        public Optional<Order> findById(long orderId) {
            if (transaction != null) {
                assertThat(transaction.active).isTrue();
            }
            finds++;
            return Optional.of(order);
        }

        @Override
        public void save(Order order) {
            if (transaction != null) {
                assertThat(transaction.active).isTrue();
            }
            saves++;
        }
    }

    private static final class RecordingTransactionBoundary implements TransactionBoundary {
        private boolean active;
        private int calls;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            calls++;
            active = true;
            try {
                return work.get();
            } finally {
                active = false;
            }
        }
    }
}
