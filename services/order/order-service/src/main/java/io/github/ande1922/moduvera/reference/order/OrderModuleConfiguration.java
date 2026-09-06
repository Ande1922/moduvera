package io.github.ande1922.moduvera.reference.order;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.reference.order.application.InventoryResultHandler;
import io.github.ande1922.moduvera.reference.order.application.OrderApplicationService;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderModuleConfiguration {

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

    @Bean
    InventoryResultHandler inventoryResultHandler(
            OrderRepository orders,
            InboxRepository inboxRepository,
            TransactionBoundary transactions,
            UseCaseAuthorizer authorizer,
            Clock clock) {
        return new InventoryResultHandler(
                new InboxTemplate("order-inventory-result", inboxRepository, transactions, clock),
                orders,
                authorizer);
    }
}
