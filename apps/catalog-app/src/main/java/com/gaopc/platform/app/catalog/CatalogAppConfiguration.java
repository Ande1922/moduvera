package com.gaopc.platform.app.catalog;

import com.gaopc.platform.authorization.UseCaseAuthorizer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CatalogAppConfiguration {

    @Bean
    Clock catalogClock() {
        return Clock.systemUTC();
    }

    @Bean
    UseCaseAuthorizer catalogAuthorizer() {
        return new UseCaseAuthorizer();
    }
}
