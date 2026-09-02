package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CatalogClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class,
                    HttpServiceClientPropertiesAutoConfiguration.class,
                    RestClientAutoConfiguration.class,
                    HttpServiceClientAutoConfiguration.class))
            .withUserConfiguration(RemoteCatalogApiConfiguration.class)
            .withBean(InternalAccessTokenProvider.class, () -> () -> "catalog-token");

    @Test
    void rejectsMissingCatalogBaseUrlDuringStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("spring.http.serviceclient.catalog.base-url");
        });
    }

    @Test
    void rejectsAConfiguredCatalogBaseUrlThatIsNotAbsoluteHttp() {
        contextRunner
                .withPropertyValues("spring.http.serviceclient.catalog.base-url=/catalog")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "spring.http.serviceclient.catalog.base-url must be an absolute HTTP(S) URL");
                });
    }

    @Test
    void registersTheNamedCatalogGroupWithIndependentTimeouts() {
        contextRunner
                .withPropertyValues(
                        "spring.http.serviceclient.catalog.base-url=https://catalog.test",
                        "spring.http.serviceclient.catalog.connect-timeout=175ms",
                        "spring.http.serviceclient.catalog.read-timeout=925ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CatalogApi.class);
                    assertThat(context).hasSingleBean(CatalogLookupTransport.class);
                    var catalog = context.getBean(HttpServiceClientProperties.class).get("catalog");
                    assertThat(catalog).isNotNull();
                    assertThat(catalog.getBaseUrl()).isEqualTo("https://catalog.test");
                    assertThat(catalog.getConnectTimeout()).isEqualTo(Duration.ofMillis(175));
                    assertThat(catalog.getReadTimeout()).isEqualTo(Duration.ofMillis(925));
                });
    }

    @Test
    void rejectsAClientFactoryOtherThanApacheHc5() {
        contextRunner
                .withPropertyValues(
                        "spring.http.clients.imperative.factory=jdk",
                        "spring.http.serviceclient.catalog.base-url=https://catalog.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "The catalog HTTP Service Client Group requires Apache HC5");
                });
    }

    @Test
    void honorsTheNamedCatalogGroupReadTimeoutOnTheActualTransport() {
        AtomicInteger requests = new AtomicInteger();
        HttpServer catalog = server(exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(500);
                respond(
                        exchange,
                        200,
                        "{\"productId\":100,\"name\":\"Keyboard\",\"unitPrice\":399.00,"
                                + "\"currency\":\"CNY\",\"version\":3}");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Expected when the client closes the timed-out exchange.
            }
        });
        try {
            contextRunner
                    .withPropertyValues(
                            "spring.http.clients.imperative.factory=http-components",
                            "spring.http.serviceclient.catalog.base-url=" + baseUrl(catalog),
                            "spring.http.serviceclient.catalog.connect-timeout=1s",
                            "spring.http.serviceclient.catalog.read-timeout=50ms")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        CatalogApi client = context.getBean(CatalogApi.class);
                        assertThatThrownBy(() -> ExecutionContextHolder.call(
                                        executionContext(),
                                        () -> client.getProduct(new GetProductQuery(100))))
                                .isInstanceOf(CatalogCallException.class)
                                .hasMessage("Catalog product lookup failed")
                                .hasRootCauseInstanceOf(SocketTimeoutException.class);
                        assertThat(requests).hasValue(1);
                    });
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void doesNotRetryAProductLookupAfterTheServerDropsTheConnection() {
        AtomicInteger requests = new AtomicInteger();
        HttpServer catalog = server(exchange -> {
            requests.incrementAndGet();
            exchange.close();
        });
        try {
            contextRunner
                    .withPropertyValues(
                            "spring.http.clients.imperative.factory=http-components",
                            "spring.http.serviceclient.catalog.base-url=" + baseUrl(catalog),
                            "spring.http.serviceclient.catalog.connect-timeout=1s",
                            "spring.http.serviceclient.catalog.read-timeout=1s")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        CatalogApi client = context.getBean(CatalogApi.class);
                        assertThatThrownBy(() -> ExecutionContextHolder.call(
                                        executionContext(),
                                        () -> client.getProduct(new GetProductQuery(100))))
                                .isInstanceOf(CatalogCallException.class)
                                .hasMessage("Catalog product lookup failed");
                        assertThat(requests).hasValue(1);
                    });
        } finally {
            catalog.stop(0);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/api/v1/catalog/products/100", handler);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static ExecutionContext executionContext() {
        return ExecutionContext.initiatedBy(
                new TenantId("tenant-a"), new Actor(ActorType.USER, "alice"), "corr-catalog");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
