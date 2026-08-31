package com.gaopc.platform.order.inbound.http;

import com.gaopc.platform.authorization.PermissionDeniedException;
import com.gaopc.platform.order.application.OrderNotFoundException;
import com.gaopc.platform.order.api.OrderApi;
import com.gaopc.platform.web.ProblemStatusContributor;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration(proxyBeanMethods = false)
public class OrderHttpInboundConfiguration {

    @Bean
    OrderHttpController orderHttpController(OrderApi orders) {
        return new OrderHttpController(orders);
    }

    @Bean
    ProblemStatusContributor orderProblemStatusContributor() {
        return exception -> {
            if (exception instanceof OrderNotFoundException) {
                return Optional.of(HttpStatus.NOT_FOUND);
            }
            if (exception instanceof PermissionDeniedException) {
                return Optional.of(HttpStatus.FORBIDDEN);
            }
            return Optional.empty();
        };
    }
}
