package com.gaopc.platform.order.configuration;

import com.gaopc.platform.authorization.UseCaseAuthorizer;
import com.gaopc.platform.catalog.api.CatalogApi;
import com.gaopc.platform.data.TransactionBoundary;
import com.gaopc.platform.identifier.IdentifierGenerator;
import com.gaopc.platform.order.application.OrderApplicationService;
import com.gaopc.platform.order.application.ReserveInventoryPublisher;
import com.gaopc.platform.order.domain.OrderRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderApplicationConfiguration {

    @Bean
    OrderApplicationService orderApplicationService(
            CatalogApi catalog,
            OrderRepository orders,
            ReserveInventoryPublisher publisher,
            IdentifierGenerator identifiers,
            TransactionBoundary transactions,
            UseCaseAuthorizer authorizer,
            Clock clock) {
        return new OrderApplicationService(
                catalog, orders, publisher, identifiers, transactions, authorizer, clock);
    }
}
