package io.github.ande1922.moduvera.reference.app.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.autoconfigure.WebHttpHandlerBuilderCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
class GatewayConfiguration {

    static final String IDENTITY_KEY = "identity";
    static final String ORDER_KEY = "order";
    static final String IDENTITY_PUBLIC_PREFIX = "/api/" + IDENTITY_KEY;
    static final String ORDER_PUBLIC_PREFIX = "/api/" + ORDER_KEY;

    @Bean
    IdentityTokenExchangeClient identityTokenExchangeClient(GatewayProperties properties) {
        String basicAuthorization = "Basic " + Base64.getEncoder()
                .encodeToString((properties.getServiceId() + ":" + properties.getServiceSecret())
                        .getBytes(StandardCharsets.UTF_8));
        WebClient identity = WebClient.builder()
                .baseUrl(properties.getIdentityBaseUrl().toString())
                .build();
        return new IdentityTokenExchangeClient(
                identity, basicAuthorization, properties.getTimeout());
    }

    @Bean
    GatewayProblemWriter gatewayProblemWriter(ObjectMapper json) {
        return new GatewayProblemWriter(json);
    }

    @Bean
    OrderSessionGatewayFilter orderSessionGatewayFilter(
            IdentityTokenExchangeClient identities, GatewayProblemWriter problems) {
        return new OrderSessionGatewayFilter(identities, problems);
    }

    @Bean
    WebHttpHandlerBuilderCustomizer gatewayRequestDiagnostics() {
        return builder -> builder
                .filters(filters -> filters.addFirst(GatewayRequestDiagnostics::bind))
                .httpHandlerDecorator(GatewayRequestDiagnostics::decorate);
    }

    @Bean
    GatewayErrorHandler gatewayErrorHandler(GatewayProblemWriter problems) {
        return new GatewayErrorHandler(problems);
    }

    @Bean
    RouteLocator publicRoutes(
            RouteLocatorBuilder routes,
            GatewayProperties properties,
            OrderSessionGatewayFilter orderSession) {
        String loginPath = IDENTITY_PUBLIC_PREFIX + "/v1/session/login";
        String ordersPath = ORDER_PUBLIC_PREFIX + "/v1/orders";
        return routes.routes()
                .route(IDENTITY_KEY, route -> route.path(loginPath)
                        .and()
                        .method(HttpMethod.POST)
                        .filters(filters -> filters.removeRequestHeader(HttpHeaders.AUTHORIZATION)
                                .removeRequestHeader("Tenant-Id")
                                .stripPrefix(2))
                        .uri(properties.getIdentityBaseUrl()))
                .route(ORDER_KEY, route -> route.path(ordersPath, ordersPath + "/**")
                        .and()
                        .method(HttpMethod.GET, HttpMethod.POST)
                        .filters(filters -> {
                            filters.filter(orderSession).removeRequestHeader("Tenant-Id");
                            if (!properties.isOrderTargetPreservesPrefix()) {
                                filters.stripPrefix(2);
                            }
                            return filters.rewriteResponseHeader(
                                    HttpHeaders.LOCATION,
                                    "^/v1/orders(?<remainder>/.*)?$",
                                    ORDER_PUBLIC_PREFIX + "/v1/orders${remainder}");
                        })
                        .uri(properties.getOrderBaseUrl()))
                .build();
    }
}
