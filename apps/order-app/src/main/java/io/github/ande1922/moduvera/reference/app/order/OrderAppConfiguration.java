package io.github.ande1922.moduvera.reference.app.order;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.identifier.SnowflakeIdentifierGenerator;
import io.github.ande1922.moduvera.security.web.ExecutionContextHandlerSelection;
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
    IdentifierGenerator orderIdentifiers(@Value("${moduvera.identifier.worker-id}") long workerId) {
        return new SnowflakeIdentifierGenerator(workerId);
    }

    @Bean
    UseCaseAuthorizer orderAuthorizer() {
        return new UseCaseAuthorizer();
    }

    @Bean
    ExecutionContextHandlerSelection orderHttpExecutionHandlers() {
        return ExecutionContextHandlerSelection.builder()
                .managePackage("io.github.ande1922.moduvera.reference.order.adapter.inbound.http")
                .excludePackage("org.springframework.boot.actuate")
                .excludePackage("org.springframework.boot.autoconfigure.web.servlet.error")
                .build();
    }
}
