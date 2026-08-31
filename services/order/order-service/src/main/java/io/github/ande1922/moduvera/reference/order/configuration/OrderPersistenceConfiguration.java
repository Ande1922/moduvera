package io.github.ande1922.moduvera.reference.order.configuration;

import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import io.github.ande1922.moduvera.reference.order.infrastructure.messaging.OutboxReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.infrastructure.persistence.MybatisOrderRepository;
import io.github.ande1922.moduvera.reference.order.infrastructure.persistence.OrderMapper;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = OrderMapper.class)
public class OrderPersistenceConfiguration {

    @Bean
    OrderRepository orderRepository(OrderMapper mapper, Clock clock) {
        return new MybatisOrderRepository(mapper, clock);
    }

    @Bean
    ReserveInventoryPublisher reserveInventoryPublisher(
            DurablePublication outbox, ObjectMapper json, Clock clock) {
        return new OutboxReserveInventoryPublisher(outbox, json, clock);
    }
}
