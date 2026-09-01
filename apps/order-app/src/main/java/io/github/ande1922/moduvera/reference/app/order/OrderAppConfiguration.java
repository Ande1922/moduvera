package io.github.ande1922.moduvera.reference.app.order;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.identifier.SnowflakeIdentifierGenerator;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrderAppConfiguration {

    @Bean
    Clock orderClock() {
        return Clock.systemUTC();
    }

    @Bean
    IdentifierGenerator orderIdentifiers(@Value("${moduvera.identifier.worker-id:1}") long workerId) {
        return new SnowflakeIdentifierGenerator(workerId);
    }

    @Bean
    UseCaseAuthorizer orderAuthorizer() {
        return new UseCaseAuthorizer();
    }
}
