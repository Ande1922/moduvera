package io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence;

import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = OrderMapper.class)
public class OrderPersistenceConfiguration {

    @Bean
    OrderRepository orderRepository(OrderMapper mapper, Clock clock) {
        return new MybatisOrderRepository(mapper, clock);
    }
}
