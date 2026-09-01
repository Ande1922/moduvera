package io.github.ande1922.moduvera.reference.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.order.api.CreateOrderCommand;
import io.github.ande1922.moduvera.reference.order.api.CreateOrderLine;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import io.github.ande1922.moduvera.testing.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderApplicationServiceTest {

    @Test
    void placesPendingOrderAndRecordsReserveIntentInOneExplicitWorkUnit() {
        var repository = new RecordingOrderRepository();
        var publisher = new RecordingReserveInventoryPublisher();
        var transactions = new RecordingTransactionBoundary();
        CatalogApi catalog = query -> new ProductSnapshot(
                query.productId(), "Coffee", new BigDecimal("18.00"), Currency.getInstance("CNY"), 1);
        var service = new OrderApplicationService(
                catalog,
                repository,
                publisher,
                () -> 42,
                transactions,
                new UseCaseAuthorizer(),
                MutableClock.atUtc(Instant.parse("2026-08-30T00:00:00Z")));

        var placed = ExecutionContextHolder.call(userContext("tenant-a"), () -> service.create(
                new CreateOrderCommand(List.of(new CreateOrderLine(7, 2)))));

        assertThat(placed.orderId()).isEqualTo(42);
        assertThat(placed.status()).isEqualTo(OrderStatus.PENDING_STOCK);
        assertThat(placed.total()).isEqualByComparingTo("36.00");
        assertThat(transactions.executions).isEqualTo(1);
        assertThat(repository.saved()).singleElement().satisfies(order -> {
            assertThat(order.id()).isEqualTo(42);
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_STOCK);
        });
        assertThat(publisher.published()).singleElement().satisfies(command -> {
            assertThat(command.orderId()).isEqualTo(42);
            assertThat(command.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(2));
        });
    }

    private static ExecutionContext userContext(String tenantId) {
        Actor actor = new Actor(
                ActorType.USER,
                "alice",
                Set.of(
                        OrderApplicationService.CREATE.value(),
                        OrderApplicationService.READ.value()));
        return ExecutionContext.initiatedBy(new TenantId(tenantId), actor, "corr-order");
    }

    private static final class RecordingOrderRepository implements OrderRepository {

        private final List<Order> saved = new ArrayList<>();

        @Override
        public Optional<Order> findById(long orderId) {
            throw new UnsupportedOperationException("recording repository does not emulate reads");
        }

        @Override
        public void save(Order order) {
            saved.add(order);
        }

        private List<Order> saved() {
            return List.copyOf(saved);
        }
    }

    private static final class RecordingReserveInventoryPublisher implements ReserveInventoryPublisher {

        private final List<ReserveInventoryCommand> published = new ArrayList<>();

        @Override
        public void publish(ReserveInventoryCommand command) {
            published.add(command);
        }

        private List<ReserveInventoryCommand> published() {
            return List.copyOf(published);
        }
    }

    private static final class RecordingTransactionBoundary implements TransactionBoundary {

        private int executions;

        @Override
        public <T> T inTransaction(java.util.function.Supplier<T> work) {
            executions++;
            return work.get();
        }
    }
}
