package io.github.ande1922.moduvera.reference.order.adapter.inbound.http;

import io.github.ande1922.moduvera.authorization.PermissionDeniedException;
import io.github.ande1922.moduvera.reference.order.application.OrderNotFoundException;
import io.github.ande1922.moduvera.reference.order.api.OrderApi;
import io.github.ande1922.moduvera.web.ProblemStatusContributor;
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
