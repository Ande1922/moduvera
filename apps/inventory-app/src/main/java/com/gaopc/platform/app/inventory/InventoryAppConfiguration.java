package com.gaopc.platform.app.inventory;

import com.gaopc.platform.authorization.UseCaseAuthorizer;
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
