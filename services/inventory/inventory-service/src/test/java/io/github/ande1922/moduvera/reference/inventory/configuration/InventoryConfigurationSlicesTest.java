package io.github.ande1922.moduvera.reference.inventory.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryApi;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReserved;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class InventoryConfigurationSlicesTest {

    @Test
    void applicationSliceCanBeSelectedWithoutPersistenceOrMessagingInbound() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(InventoryStore.class, () -> (command, now) -> null);
            context.registerBean(InventoryResultPublisher.class, () -> result -> {});
            context.registerBean(UseCaseAuthorizer.class, UseCaseAuthorizer::new);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(InventoryApplicationConfiguration.class);

            context.refresh();

            assertThat(context.getBeansOfType(InventoryApplicationService.class)).hasSize(1);
            assertThat(context.getBeansOfType(Consumer.class)).isEmpty();
        }
    }

    @Test
    void messagingInboundSliceCanBeSelectedWithoutApplicationOrPersistence() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(InventoryApi.class, () -> command -> new InventoryReserved(
                    command.commandId(), command.orderId(), Instant.EPOCH));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(
                    ReliableMessageConsumerFactory.class,
                    InventoryConfigurationSlicesTest::consumerFactory);
            context.register(ReserveInventoryCommandInboundConfiguration.class);

            context.refresh();

            assertThat(context.getBeansOfType(Consumer.class)).hasSize(1);
            assertThat(context.getBeansOfType(InventoryApplicationService.class)).isEmpty();
            assertThat(context.getBeansOfType(InventoryStore.class)).isEmpty();
        }
    }

    private static ReliableMessageConsumerFactory consumerFactory() {
        InboxRepository inbox = (tenantId, consumerId, messageId, processedAt) -> true;
        TransactionBoundary transactions = new TransactionBoundary() {
            @Override
            public <T> T inTransaction(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
        return new ReliableMessageConsumerFactory(
                new KafkaMessageMapper(), inbox, transactions, Clock.systemUTC());
    }
}
