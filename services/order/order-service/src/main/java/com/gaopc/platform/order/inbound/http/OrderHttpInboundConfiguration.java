package com.gaopc.platform.order.inbound.http;

import com.gaopc.platform.order.api.OrderApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderHttpInboundConfiguration {

    @Bean
    OrderHttpController orderHttpController(OrderApi orders) {
        return new OrderHttpController(orders);
    }
}
