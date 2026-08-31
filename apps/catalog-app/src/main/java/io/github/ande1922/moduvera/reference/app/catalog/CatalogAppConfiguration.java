package io.github.ande1922.moduvera.reference.app.catalog;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
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
