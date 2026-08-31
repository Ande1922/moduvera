package com.gaopc.platform.app.monolith;

import com.gaopc.platform.authorization.PermissionDeniedException;
import com.gaopc.platform.authorization.UseCaseAuthorizer;
import com.gaopc.platform.catalog.CatalogApplicationConfiguration;
import com.gaopc.platform.catalog.CatalogInternalHttpConfiguration;
import com.gaopc.platform.catalog.CatalogPersistenceConfiguration;
import com.gaopc.platform.catalog.application.ProductNotFoundException;
import com.gaopc.platform.identifier.IdentifierGenerator;
import com.gaopc.platform.identifier.SnowflakeIdentifierGenerator;
import com.gaopc.platform.inventory.configuration.InventoryApplicationConfiguration;
import com.gaopc.platform.inventory.configuration.InventoryPersistenceConfiguration;
import com.gaopc.platform.inventory.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import com.gaopc.platform.migration.DatabaseComponent;
import com.gaopc.platform.migration.DatabaseMigrator;
import com.gaopc.platform.migration.MigrationPlan;
import com.gaopc.platform.order.application.OrderNotFoundException;
import com.gaopc.platform.order.configuration.OrderApplicationConfiguration;
import com.gaopc.platform.order.configuration.OrderPersistenceConfiguration;
import com.gaopc.platform.order.inbound.http.OrderHttpController;
import com.gaopc.platform.order.inbound.http.OrderHttpInboundConfiguration;
import com.gaopc.platform.order.inbound.messaging.InventoryResultInboundConfiguration;
import com.gaopc.platform.web.ProblemStatusResolver;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
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
    IdentifierGenerator orderIdentifiers(@Value("${platform.identifier.worker-id:1}") long workerId) {
        return new SnowflakeIdentifierGenerator(workerId);
    }

    @Bean
    UseCaseAuthorizer businessAuthorizer() {
        return new UseCaseAuthorizer();
    }

    @Bean
    @Primary
    ProblemStatusResolver businessProblemStatusResolver() {
        return exception -> {
            if (exception instanceof OrderNotFoundException
                    || exception instanceof ProductNotFoundException) {
                return HttpStatus.NOT_FOUND;
            }
            if (exception instanceof PermissionDeniedException) {
                return HttpStatus.FORBIDDEN;
            }
            return HttpStatus.UNPROCESSABLE_CONTENT;
        };
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
