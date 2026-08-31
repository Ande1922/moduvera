package com.gaopc.platform.inventory.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaopc.platform.authorization.UseCaseAuthorizer;
import com.gaopc.platform.data.TransactionBoundary;
import com.gaopc.platform.inventory.api.InventoryApi;
import com.gaopc.platform.inventory.api.InventoryReserved;
import com.gaopc.platform.inventory.application.InventoryApplicationService;
import com.gaopc.platform.inventory.application.InventoryResultPublisher;
import com.gaopc.platform.inventory.domain.InventoryStore;
import com.gaopc.platform.inventory.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import com.gaopc.platform.message.inbox.InboxRepository;
import com.gaopc.platform.messaging.kafka.KafkaMessageMapper;
import com.gaopc.platform.messaging.kafka.ReliableMessageConsumerFactory;
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
