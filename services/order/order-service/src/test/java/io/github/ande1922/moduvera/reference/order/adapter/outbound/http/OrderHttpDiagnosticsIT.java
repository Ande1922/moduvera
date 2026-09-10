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
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = OrderHttpDiagnosticsIT.Configuration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.application.name=order-http-fixture")
class OrderHttpDiagnosticsIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final List<Receipt> RECEIPTS = new CopyOnWriteArrayList<>();
    private static final CountDownLatch BODY_STARTED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_BODY = new CountDownLatch(1);
    private static final HttpServer PEER = server();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }
        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        String base = "http://localhost:" + PEER.getAddress().getPort();
        properties.add("spring.http.serviceclient.identity.base-url", () -> base + "/identity-prefix?search=outbound-query-sentinel");
        properties.add("spring.http.serviceclient.catalog.base-url", () -> base + "/catalog-prefix?search=outbound-query-sentinel");
        properties.add("spring.http.serviceclient.identity.read-timeout", () -> "500ms");
        properties.add("spring.http.serviceclient.catalog.read-timeout", () -> "500ms");
        properties.add("spring.http.clients.imperative.factory", () -> "http-components");
        properties.add("moduvera.reference.clients.identity.service-id", () -> "order-service");
        properties.add("moduvera.reference.clients.identity.service-secret", () -> UUID.randomUUID().toString());
    }

    @Autowired
    private CatalogApi catalog;

    private io.opentelemetry.context.Scope operationScope;

    @BeforeEach
    void enterSuppliedOperationContext() {
        // The ticket exercises client seams independently of a complete HTTP ingress.
        // Use the actual Agent's standard propagator; this does not create a fixture Span.
        Context operation = GlobalOpenTelemetry.getPropagators().getTextMapPropagator().extract(Context.root(),
                Map.of("traceparent", "00-" + UUID.randomUUID().toString().replace("-", "") + "-1234567890abcdef-01",
                        "tracestate", "fixture=retained"), GETTER);
        operationScope = operation.makeCurrent();
    }

    @AfterEach
    void restoreOperationContext() {
        operationScope.close();
    }

    @AfterAll
    static void stop() {
        RELEASE_BODY.countDown();
        PEER.stop(0);
        WORKERS.close();
        if (Boolean.getBoolean("moduvera.test.agent")) {
            RECEIPTS.forEach(receipt -> System.out.println(JSON.writeValueAsString(Map.of("http_fixture_receipt", receipt))));
        }
    }

    @Test
    void realNamedClientsPropagateAndCacheAcrossDifferentCorrelationsAndTraceContexts() {
        boolean agent = Boolean.getBoolean("moduvera.test.agent");
        for (String flags : List.of("01", "00")) {
            String tenant = "cache-" + flags;
            String trace = flags.equals("01") ? "11111111111111111111111111111111" : "22222222222222222222222222222222";
            String secondTrace = flags.equals("01") ? "77777777777777777777777777777777" : "88888888888888888888888888888888";
            String parent = "00-" + trace + "-1234567890abcdef-" + flags;
            Context telemetry = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                    .extract(Context.root(), Map.of("traceparent", parent, "tracestate", "fixture=retained"), GETTER);
            String first = UUID.randomUUID().toString();
            String second = UUID.randomUUID().toString();
            try (HttpClientLogProbe logs = new HttpClientLogProbe(); var ignored = telemetry.makeCurrent()) {
                assertThat(ExecutionContextHolder.call(context(tenant, first), () -> catalog.getProduct(new GetProductQuery(100))).productId())
                        .isEqualTo(100);
                Context nextOperation = GlobalOpenTelemetry.getPropagators().getTextMapPropagator().extract(Context.root(),
                        Map.of("traceparent", "00-" + secondTrace + "-1234567890abcdef-" + flags,
                                "tracestate", "fixture=retained"), GETTER);
                try (var nextScope = nextOperation.makeCurrent()) {
                    assertThat(ExecutionContextHolder.call(context(tenant, second), () -> catalog.getProduct(new GetProductQuery(100))).productId())
                            .isEqualTo(100);
                }
                var receipts = RECEIPTS.stream().filter(value -> List.of(first, second).contains(value.correlation())).toList();
                assertThat(receipts).hasSize(3);
                assertThat(receipts.stream().filter(value -> value.target().equals("identity-service"))).hasSize(1);
                assertThat(receipts.stream().filter(value -> value.target().equals("catalog-service"))).hasSize(2);
                assertThat(logs.events()).hasSize(3).allSatisfy(event -> {
                    assertThat(event.path("event").path("outcome").asString()).isEqualTo("success");
                    assertThat(event.path("log").path("level").asString()).isEqualTo("INFO");
                    assertThat(event.path("tenant_id").asString()).isEqualTo(tenant);
                    assertThat(event.path("actor_id").asString()).isEqualTo("client-fixture-user");
                    assertThat(event.path("correlation_id").asString()).isIn(first, second);
                    assertThat(event.path("http").path("response").path("body").path("bytes").asLong()).isPositive();
                    assertThat(event.path("duration_ms").asDouble()).isGreaterThanOrEqualTo(0);
                    assertThat(event.has("retry")).isFalse();
                    assertThat(event.path("error").has("stack_trace")).isFalse();
                    assertThat(event.path("error").has("message")).isFalse();
                    assertThat(event.toString()).doesNotContain("outbound-query-sentinel", "outbound-token-sentinel");
                    if (agent) {
                        assertThat(event.path("trace_id").asString()).isEqualTo(
                                event.path("correlation_id").asString().equals(first) ? trace : secondTrace);
                        assertThat(event.path("span_id").asString()).matches("[0-9a-f]{16}");
                    }
                });
                assertThat(logs.events().stream().map(value -> value.path("url").path("path").asString()).toList())
                        .contains("/identity-prefix/internal/api/v1/service-token", "/catalog-prefix/internal/api/v1/catalog/products/100");
                if (agent) {
                    assertThat(receipts).allSatisfy(receipt -> {
                        assertThat(receipt.traceparent()).startsWith("00-" +
                                (receipt.correlation().equals(first) ? trace : secondTrace) + "-").endsWith("-" + flags);
                        assertThat(receipt.tracestate()).isEqualTo("fixture=retained");
                    });
                }
            }
        }
    }

    @Test
    void rejectedIdentityAttemptDoesNotInventACatalogCallOrRecovery() {
        String correlation = UUID.randomUUID().toString();
        try (HttpClientLogProbe logs = new HttpClientLogProbe()) {
            assertThatThrownBy(() -> ExecutionContextHolder.call(context("identity-rejected", correlation),
                    () -> catalog.getProduct(new GetProductQuery(100))))
                    .isInstanceOf(CatalogCallException.class).hasMessage("Identity rejected the service token request with HTTP 503");
            assertThat(RECEIPTS.stream().filter(value -> correlation.equals(value.correlation()))).hasSize(1);
            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();
            assertThat(event.path("target").asString()).isEqualTo("identity-service");
            assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
            assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(503);
            assertThat(event.path("log").path("level").asString()).isEqualTo("INFO");
            assertThat(event.has("retry")).isFalse();
                    assertThat(event.path("error").has("stack_trace")).isFalse();
                    assertThat(event.path("error").has("message")).isFalse();
        }
    }

    @Test
    void noResponseFailureHasNoStatusAndOneObservableAttempt() {
        String correlation = UUID.randomUUID().toString();
        try (HttpClientLogProbe logs = new HttpClientLogProbe()) {
            assertThatThrownBy(() -> ExecutionContextHolder.call(context("connection-drop", correlation),
                    () -> catalog.getProduct(new GetProductQuery(902))))
                    .isInstanceOf(CatalogCallException.class).hasMessage("Catalog product lookup failed");
            assertThat(RECEIPTS.stream().filter(value -> correlation.equals(value.correlation()))).hasSize(2);
            var event = logs.events().stream().filter(value -> value.path("target").asString().equals("catalog-service"))
                    .findFirst().orElseThrow();
            assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
            assertThat(event.path("http").has("response")).isFalse();
            assertThat(event.path("error").path("type").asString()).isNotBlank();
            assertThat(event.path("log").path("level").asString()).isEqualTo("INFO");
            assertThat(logs.events()).hasSize(2);
        }
    }

    @Test
    void bodyReadTimeoutAfterHeadersIsFailureAndDoesNotCompleteEarly() throws Exception {
        String correlation = UUID.randomUUID().toString();
        try (HttpClientLogProbe logs = new HttpClientLogProbe()) {
            java.util.concurrent.Callable<io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot> operation =
                    () -> ExecutionContextHolder.call(context("body-timeout", correlation),
                            () -> catalog.getProduct(new GetProductQuery(903)));
            var future = WORKERS.submit(Context.current().wrap(operation));
            assertThat(BODY_STARTED.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(logs.events()).noneSatisfy(event -> assertThat(event.path("target").asString()).isEqualTo("catalog-service"));
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CatalogCallException.class);
            var event = logs.events().stream().filter(value -> value.path("target").asString().equals("catalog-service"))
                    .findFirst().orElseThrow();
            assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
            assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
            assertThat(event.path("duration_ms").asDouble()).isGreaterThanOrEqualTo(400);
            assertThat(event.path("error").path("type").asString()).contains("Timeout");
            assertThat(logs.events()).hasSize(2);
        } finally {
            RELEASE_BODY.countDown();
        }
    }

    private static ExecutionContext context(String tenant, String correlation) {
        return ExecutionContext.initiatedBy(new TenantId(tenant), new Actor(ActorType.USER, "client-fixture-user"), correlation);
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", OrderHttpDiagnosticsIT::respond);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean identity = path.startsWith("/identity-prefix/");
        String tenant = identity ? JSON.readTree(request).path("tenantId").asString() : exchange.getRequestHeaders().getFirst("Tenant-Id");
        RECEIPTS.add(new Receipt(identity ? "identity-service" : "catalog-service", path,
                exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                exchange.getRequestHeaders().getFirst("traceparent"), exchange.getRequestHeaders().getFirst("tracestate")));
        assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("search=outbound-query-sentinel");
        if (path.endsWith("/902")) {
            exchange.close();
            return;
        }
        String body = identity ? JSON.writeValueAsString(Map.of("accessToken", "outbound-token-sentinel", "expiresAt", Instant.now().plusSeconds(300)))
                : "{\"productId\":100,\"name\":\"fixture\",\"unitPrice\":1,\"currency\":\"CNY\",\"version\":1}";
        int status = tenant.equals("identity-rejected") ? 503 : 200;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange) {
            if (path.endsWith("/903")) {
                exchange.getResponseBody().write(bytes, 0, 1);
                exchange.getResponseBody().flush();
                BODY_STARTED.countDown();
                try {
                    RELEASE_BODY.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            exchange.getResponseBody().write(bytes);
        }
    }

    private record Receipt(String target, String path, String correlation, String traceparent, String tracestate) {}

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import({IdentityServiceTokenClientConfiguration.class, RemoteCatalogApiConfiguration.class})
    @ImportAutoConfiguration({HttpClientAutoConfiguration.class, ImperativeHttpClientAutoConfiguration.class,
            HttpServiceClientPropertiesAutoConfiguration.class, RestClientAutoConfiguration.class, HttpServiceClientAutoConfiguration.class})
    static class Configuration {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
