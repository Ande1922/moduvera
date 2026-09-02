package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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

class IdentityServiceTokenClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class,
                    HttpServiceClientPropertiesAutoConfiguration.class,
                    RestClientAutoConfiguration.class,
                    HttpServiceClientAutoConfiguration.class))
            .withUserConfiguration(IdentityServiceTokenClientConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues(
                    "moduvera.reference.clients.identity.service-id=order-service",
                    "moduvera.reference.clients.identity.service-secret=order-secret");

    @Test
    void rejectsMissingIdentityBaseUrlDuringStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("spring.http.serviceclient.identity.base-url");
        });
    }

    @Test
    void rejectsAConfiguredIdentityBaseUrlThatIsNotAbsoluteHttp() {
        contextRunner
                .withPropertyValues("spring.http.serviceclient.identity.base-url=/identity")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "spring.http.serviceclient.identity.base-url must be an absolute HTTP(S) URL");
                });
    }

    @Test
    void registersTheNamedIdentityGroupWithIndependentTimeouts() {
        contextRunner
                .withPropertyValues(
                        "spring.http.serviceclient.identity.base-url=https://identity.test",
                        "spring.http.serviceclient.identity.connect-timeout=125ms",
                        "spring.http.serviceclient.identity.read-timeout=750ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InternalAccessTokenProvider.class);
                    assertThat(context).hasSingleBean(IdentityServiceTokenTransport.class);
                    var identity = context.getBean(HttpServiceClientProperties.class).get("identity");
                    assertThat(identity).isNotNull();
                    assertThat(identity.getBaseUrl()).isEqualTo("https://identity.test");
                    assertThat(identity.getConnectTimeout()).isEqualTo(Duration.ofMillis(125));
                    assertThat(identity.getReadTimeout()).isEqualTo(Duration.ofMillis(750));
                });
    }

    @Test
    void rejectsAClientFactoryOtherThanApacheHc5() {
        contextRunner
                .withPropertyValues(
                        "spring.http.clients.imperative.factory=jdk",
                        "spring.http.serviceclient.identity.base-url=https://identity.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "The identity HTTP Service Client Group requires Apache HC5");
                });
    }

    @Test
    void doesNotRetryARejectedServiceTokenPost() {
        AtomicInteger requests = new AtomicInteger();
        HttpServer identity = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 503, "{\"status\":503}");
        });
        try {
            contextRunner
                    .withPropertyValues(
                            "spring.http.clients.imperative.factory=http-components",
                            "spring.http.serviceclient.identity.base-url=" + baseUrl(identity),
                            "spring.http.serviceclient.identity.connect-timeout=1s",
                            "spring.http.serviceclient.identity.read-timeout=1s")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var provider = context.getBean(InternalAccessTokenProvider.class);
                        assertThatThrownBy(() -> ExecutionContextHolder.call(
                                        executionContext(), provider::accessToken))
                                .isInstanceOf(CatalogCallException.class)
                                .hasMessage("Identity rejected the service token request with HTTP 503");
                        assertThat(requests).hasValue(1);
                    });
        } finally {
            identity.stop(0);
        }
    }

    @Test
    void honorsTheNamedIdentityGroupReadTimeoutOnTheActualTransport() {
        AtomicInteger requests = new AtomicInteger();
        HttpServer identity = server(exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(500);
                respond(
                        exchange,
                        200,
                        "{\"accessToken\":\"late-token\",\"expiresAt\":\"2026-09-02T00:05:00Z\"}");
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
                            "spring.http.serviceclient.identity.base-url=" + baseUrl(identity),
                            "spring.http.serviceclient.identity.connect-timeout=1s",
                            "spring.http.serviceclient.identity.read-timeout=50ms")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var provider = context.getBean(InternalAccessTokenProvider.class);
                        assertThatThrownBy(() -> ExecutionContextHolder.call(
                                        executionContext(), provider::accessToken))
                                .isInstanceOf(CatalogCallException.class)
                                .hasMessage("Identity service token request failed")
                                .hasRootCauseInstanceOf(SocketTimeoutException.class);
                        assertThat(requests).hasValue(1);
                    });
        } finally {
            identity.stop(0);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/api/v1/service-token", handler);
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
                new TenantId("tenant-a"), new Actor(ActorType.USER, "alice"), "corr-identity");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
