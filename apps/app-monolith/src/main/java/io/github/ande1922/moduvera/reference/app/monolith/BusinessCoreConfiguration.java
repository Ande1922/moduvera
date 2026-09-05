package io.github.ande1922.moduvera.reference.app.monolith;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.identifier.SnowflakeIdentifierGenerator;
import io.github.ande1922.moduvera.messaging.kafka.migration.ModuveraMessagingMigrationConfiguration;
import io.github.ande1922.moduvera.reference.catalog.catalog.CatalogModuleConfiguration;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogHttpController;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogInternalHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence.CatalogPersistenceOutboundConfiguration;
import io.github.ande1922.moduvera.reference.catalog.migration.CatalogMigrationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.InventoryModuleConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging.InventoryResultPublicationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.InventoryPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.inventory.migration.InventoryMigrationConfiguration;
import io.github.ande1922.moduvera.reference.order.OrderModuleConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.http.OrderHttpController;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.http.OrderHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging.InventoryResultInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging.ReserveInventoryPublicationConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.OrderPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.order.migration.OrderMigrationConfiguration;
import io.github.ande1922.moduvera.security.web.ExecutionContextHandlerSelection;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Import({
    CatalogModuleConfiguration.class,
    CatalogPersistenceOutboundConfiguration.class,
    CatalogInternalHttpInboundConfiguration.class,
    CatalogMigrationConfiguration.class,
    OrderModuleConfiguration.class,
    OrderPersistenceConfiguration.class,
    ReserveInventoryPublicationConfiguration.class,
    OrderHttpInboundConfiguration.class,
    InventoryResultInboundConfiguration.class,
    OrderMigrationConfiguration.class,
    InventoryModuleConfiguration.class,
    InventoryPersistenceConfiguration.class,
    InventoryResultPublicationConfiguration.class,
    ReserveInventoryCommandInboundConfiguration.class,
    InventoryMigrationConfiguration.class,
    ModuveraMessagingMigrationConfiguration.class
})
class BusinessCoreConfiguration {

    static final String ORDER_PUBLIC_PREFIX = "/api/order";

    @Bean
    Clock businessClock() {
        return Clock.systemUTC();
    }

    @Bean
    IdentifierGenerator orderIdentifiers(@Value("${moduvera.identifier.worker-id}") long workerId) {
        return new SnowflakeIdentifierGenerator(workerId);
    }

    @Bean
    UseCaseAuthorizer businessAuthorizer() {
        return new UseCaseAuthorizer();
    }

    @Bean
    ExecutionContextHandlerSelection businessHttpExecutionHandlers() {
        return ExecutionContextHandlerSelection.builder()
                .manageHandlers(CatalogHttpController.class, OrderHttpController.class)
                .excludePackage("org.springframework.boot.actuate")
                .excludePackage("org.springframework.boot.autoconfigure.web.servlet.error")
                .build();
    }

    @Bean
    WebMvcConfigurer orderPublicPathPrefix() {
        return new WebMvcConfigurer() {
            @Override
            public void configurePathMatch(PathMatchConfigurer configurer) {
                configurer.addPathPrefix(
                        ORDER_PUBLIC_PREFIX,
                        controllerType -> OrderHttpController.class.isAssignableFrom(controllerType));
            }

        };
    }

    @Bean
    OrderPublicLocationAdvice orderPublicLocationAdvice() {
        return new OrderPublicLocationAdvice();
    }

    @ControllerAdvice(assignableTypes = OrderHttpController.class)
    static final class OrderPublicLocationAdvice implements ResponseBodyAdvice<Object> {

        @Override
        public boolean supports(
                MethodParameter returnType,
                Class<? extends HttpMessageConverter<?>> converterType) {
            return OrderHttpController.class.isAssignableFrom(returnType.getContainingClass());
        }

        @Override
        public Object beforeBodyWrite(
                Object body,
                MethodParameter returnType,
                MediaType selectedContentType,
                Class<? extends HttpMessageConverter<?>> selectedConverterType,
                ServerHttpRequest request,
                ServerHttpResponse response) {
            java.net.URI location = response.getHeaders().getLocation();
            if (location != null && location.getPath().startsWith("/v1/orders")) {
                response.getHeaders().setLocation(
                        java.net.URI.create(ORDER_PUBLIC_PREFIX + location));
            }
            return body;
        }
    }
}
