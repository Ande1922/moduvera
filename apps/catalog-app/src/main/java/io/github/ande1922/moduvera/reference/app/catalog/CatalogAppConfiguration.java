package io.github.ande1922.moduvera.reference.app.catalog;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogHttpController;
import io.github.ande1922.moduvera.security.web.ExecutionContextHandlerSelection;
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

    @Bean
    ExecutionContextHandlerSelection catalogHttpExecutionHandlers() {
        return ExecutionContextHandlerSelection.builder()
                .manageHandlers(CatalogHttpController.class)
                .excludePackage("org.springframework.boot.actuate")
                .excludePackage("org.springframework.boot.autoconfigure.web.servlet.error")
                .build();
    }
}
