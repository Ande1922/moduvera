package io.github.ande1922.moduvera.reference.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryReservationHandler;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class InventoryModuleConfigurationTest {

    @Test
    void constructsOnlyTheProtocolNeutralInventoryApplicationHandler() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(InventoryStore.class, () -> (request, now, policy) -> null);
            context.registerBean(InventoryResultPublisher.class, () -> ignored -> {});
            context.registerBean(UseCaseAuthorizer.class, UseCaseAuthorizer::new);
            context.registerBean(InboxRepository.class, AcceptingInboxRepository::new);
            context.registerBean(TransactionBoundary.class, DirectTransactionBoundary::new);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(InventoryModuleConfiguration.class);

            context.refresh();

            assertThat(context.getBeansOfType(InventoryReservationHandler.class)).hasSize(1);
            assertThat(context.getBeansOfType(ReservationPolicy.class)).hasSize(1);
            assertThat(context.getBeansOfType(Consumer.class)).isEmpty();
            assertThat(context.getBeansOfType(MigrationDefinition.class)).isEmpty();
            assertThat(context.containsBean("inventoryMapper")).isFalse();
        }
    }

    private static final class AcceptingInboxRepository implements InboxRepository {
        @Override
        public boolean isProcessed(TenantId tenantId, String consumerId, MessageId messageId) {
            return false;
        }

        @Override
        public boolean tryStart(TenantId tenantId, String consumerId, MessageId messageId, Instant processedAt) {
            return true;
        }
    }

    private static final class DirectTransactionBoundary implements TransactionBoundary {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
