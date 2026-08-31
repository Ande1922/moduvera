package com.gaopc.platform.inventory.configuration;

import com.gaopc.platform.authorization.UseCaseAuthorizer;
import com.gaopc.platform.inventory.application.InventoryApplicationService;
import com.gaopc.platform.inventory.application.InventoryResultPublisher;
import com.gaopc.platform.inventory.domain.InventoryStore;
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
