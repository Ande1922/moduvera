package io.github.ande1922.moduvera.reference.inventory.adapter.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging.InventoryResultPublicationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging.OutboxInventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.InventoryMapper;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.InventoryPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.MybatisInventoryStore;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import java.lang.reflect.Proxy;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class InventoryOutboundConfigurationSlicesTest {

    @Test
    void persistenceSliceSelectsOnlyTheMybatisStore() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("inventoryMapper", InventoryMapper.class,
                    InventoryOutboundConfigurationSlicesTest::mapperStub);
            context.register(InventoryPersistenceConfiguration.class);

            context.refresh();

            assertThat(context.getBean(InventoryStore.class))
                    .isInstanceOf(MybatisInventoryStore.class);
            assertThat(context.getBeansOfType(InventoryResultPublisher.class)).isEmpty();
            assertThat(context.getBeansOfType(MigrationDefinition.class)).isEmpty();
        }
    }

    @Test
    void publicationSliceSelectsOnlyTheOutboxPublisher() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DurablePublication.class, () -> ignored -> {});
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(InventoryResultPublicationConfiguration.class);

            context.refresh();

            assertThat(context.getBean(InventoryResultPublisher.class))
                    .isInstanceOf(OutboxInventoryResultPublisher.class);
            assertThat(context.getBeansOfType(InventoryStore.class)).isEmpty();
            assertThat(context.getBeansOfType(MigrationDefinition.class)).isEmpty();
        }
    }

    private static InventoryMapper mapperStub() {
        return (InventoryMapper) Proxy.newProxyInstance(
                InventoryMapper.class.getClassLoader(),
                new Class<?>[] {InventoryMapper.class},
                (proxy, method, arguments) -> null);
    }
}
