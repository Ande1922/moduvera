package io.github.ande1922.moduvera.reference.order.adapter.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging.OutboxReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging.ReserveInventoryPublicationConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.MybatisOrderRepository;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.OrderMapper;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.OrderPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import java.lang.reflect.Proxy;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class OrderOutboundConfigurationSlicesTest {

    @Test
    void persistenceSliceSelectsOnlyTheMybatisRepository() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("orderMapper", OrderMapper.class,
                    OrderOutboundConfigurationSlicesTest::mapperStub);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(OrderPersistenceConfiguration.class);

            context.refresh();

            assertThat(context.getBean(OrderRepository.class))
                    .isInstanceOf(MybatisOrderRepository.class);
            assertThat(context.getBeansOfType(ReserveInventoryPublisher.class)).isEmpty();
            assertThat(context.getBeansOfType(MigrationDefinition.class)).isEmpty();
        }
    }

    @Test
    void publicationSliceSelectsOnlyTheOutboxPublisher() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DurablePublication.class, () -> ignored -> {});
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(ReserveInventoryPublicationConfiguration.class);

            context.refresh();

            assertThat(context.getBean(ReserveInventoryPublisher.class))
                    .isInstanceOf(OutboxReserveInventoryPublisher.class);
            assertThat(context.getBeansOfType(OrderRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(MigrationDefinition.class)).isEmpty();
        }
    }

    private static OrderMapper mapperStub() {
        return (OrderMapper) Proxy.newProxyInstance(
                OrderMapper.class.getClassLoader(),
                new Class<?>[] {OrderMapper.class},
                (proxy, method, arguments) -> null);
    }
}
