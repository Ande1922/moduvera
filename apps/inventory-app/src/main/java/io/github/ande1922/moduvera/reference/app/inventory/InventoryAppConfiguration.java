package io.github.ande1922.moduvera.reference.app.inventory;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InventoryAppConfiguration {

    @Bean
    Clock inventoryClock() {
        return Clock.systemUTC();
    }

    @Bean
    UseCaseAuthorizer inventoryAuthorizer() {
        return new UseCaseAuthorizer();
    }
}
