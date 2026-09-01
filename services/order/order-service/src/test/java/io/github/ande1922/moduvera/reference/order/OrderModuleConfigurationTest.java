package io.github.ande1922.moduvera.reference.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.reference.order.application.OrderApplicationService;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.domain.Order;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class OrderModuleConfigurationTest {

    @Test
    void constructsOnlyTheProtocolNeutralOrderApplicationService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(CatalogApi.class, () -> query -> new ProductSnapshot(
                    query.productId(), "Coffee", new BigDecimal("18.00"), Currency.getInstance("CNY"), 1));
            context.registerBean(OrderRepository.class, EmptyOrderRepository::new);
            context.registerBean(ReserveInventoryPublisher.class, () -> ignored -> {});
            context.registerBean(IdentifierGenerator.class, () -> () -> 42);
            context.registerBean(TransactionBoundary.class, DirectTransactionBoundary::new);
            context.registerBean(UseCaseAuthorizer.class, UseCaseAuthorizer::new);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(OrderModuleConfiguration.class);

            context.refresh();

            assertThat(context.getBeansOfType(OrderApplicationService.class)).hasSize(1);
            assertThat(context.getBeansOfType(Consumer.class)).isEmpty();
            assertThat(context.getBeansOfType(MigrationDefinition.class)).isEmpty();
            assertThat(context.containsBean("orderMapper")).isFalse();
        }
    }

    private static final class EmptyOrderRepository implements OrderRepository {

        @Override
        public Optional<Order> findById(long orderId) {
            return Optional.empty();
        }

        @Override
        public void save(Order order) {}
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
