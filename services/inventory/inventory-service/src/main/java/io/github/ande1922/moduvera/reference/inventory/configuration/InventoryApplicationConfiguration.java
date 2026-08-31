package io.github.ande1922.moduvera.reference.inventory.configuration;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InventoryApplicationConfiguration {

    @Bean
    InventoryApplicationService inventoryApplicationService(
            InventoryStore inventory,
            UseCaseAuthorizer authorizer,
            Clock clock,
            InventoryResultPublisher publisher) {
        return new InventoryApplicationService(inventory, authorizer, clock, publisher);
    }
}
