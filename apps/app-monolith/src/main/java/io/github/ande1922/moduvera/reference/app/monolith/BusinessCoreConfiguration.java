package io.github.ande1922.moduvera.reference.app.monolith;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.catalog.CatalogApplicationConfiguration;
import io.github.ande1922.moduvera.reference.catalog.CatalogInternalHttpConfiguration;
import io.github.ande1922.moduvera.reference.catalog.CatalogPersistenceConfiguration;
import io.github.ande1922.moduvera.identifier.IdentifierGenerator;
import io.github.ande1922.moduvera.identifier.SnowflakeIdentifierGenerator;
import io.github.ande1922.moduvera.reference.inventory.configuration.InventoryApplicationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.configuration.InventoryPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.inventory.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import io.github.ande1922.moduvera.reference.order.configuration.OrderApplicationConfiguration;
import io.github.ande1922.moduvera.reference.order.configuration.OrderPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.order.inbound.http.OrderHttpController;
import io.github.ande1922.moduvera.reference.order.inbound.http.OrderHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.inbound.messaging.InventoryResultInboundConfiguration;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
    CatalogApplicationConfiguration.class,
    CatalogPersistenceConfiguration.class,
    CatalogInternalHttpConfiguration.class,
    OrderApplicationConfiguration.class,
    OrderPersistenceConfiguration.class,
    OrderHttpInboundConfiguration.class,
    InventoryResultInboundConfiguration.class,
    InventoryApplicationConfiguration.class,
    InventoryPersistenceConfiguration.class,
    ReserveInventoryCommandInboundConfiguration.class
})
class BusinessCoreConfiguration {

    static final String ORDER_PUBLIC_PREFIX = "/api/order";

    @Bean
    Clock businessClock() {
        return Clock.systemUTC();
    }

    @Bean
    IdentifierGenerator orderIdentifiers(@Value("${moduvera.identifier.worker-id:1}") long workerId) {
        return new SnowflakeIdentifierGenerator(workerId);
    }

    @Bean
    UseCaseAuthorizer businessAuthorizer() {
        return new UseCaseAuthorizer();
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

    @Bean
    SmartInitializingSingleton orderMigration(DataSource dataSource) {
        return () -> new DatabaseMigrator(dataSource)
                .migrate(new MigrationPlan(
                        new DatabaseComponent("order"),
                        List.of("classpath:db/migration/order"),
                        true));
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
