package com.gaopc.platform.order.configuration;

import com.gaopc.platform.message.publication.DurablePublication;
import com.gaopc.platform.order.application.ReserveInventoryPublisher;
import com.gaopc.platform.order.domain.OrderRepository;
import com.gaopc.platform.order.infrastructure.messaging.OutboxReserveInventoryPublisher;
import com.gaopc.platform.order.infrastructure.persistence.MybatisOrderRepository;
import com.gaopc.platform.order.infrastructure.persistence.OrderMapper;
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
