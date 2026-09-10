package io.github.ande1922.moduvera.reference.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = IdentityTokenExchangeClientIT.Configuration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.application.name=gateway-http-fixture")
class IdentityTokenExchangeClientIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final List<Receipt> RECEIPTS = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, Block> BLOCKS = new ConcurrentHashMap<>();
    private static final HttpServer PEER = server();
    private static final ConcurrentHashMap<String, CountDownLatch> BODY_RECEIVED = new ConcurrentHashMap<>();
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

    @BeforeAll
    static void warmRuntime() {
        reactor.netty.http.client.HttpClient.create().warmup().block(LIMIT);
    }

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
        BLOCKS.values().forEach(block -> block.release().countDown());
        PEER.stop(0);
        WORKERS.close();
        if (Boolean.getBoolean("moduvera.test.agent")) {
            RECEIPTS.forEach(receipt -> System.out.println(JSON.writeValueAsString(Map.of("http_fixture_receipt", receipt))));
        }
    }

    @Test
    void realWebClientPreservesSampledAndUnsampledPropagationAndCorrelation() {
        boolean agent = Boolean.getBoolean("moduvera.test.agent");
        for (String flags : List.of("01", "00")) {
            String trace = flags.equals("01") ? "44444444444444444444444444444444" : "55555555555555555555555555555555";
            Context telemetry = GlobalOpenTelemetry.getPropagators().getTextMapPropagator().extract(Context.root(),
                    Map.of("traceparent", "00-" + trace + "-1234567890abcdef-" + flags, "tracestate", "fixture=retained"), GETTER);
            String correlation = UUID.randomUUID().toString();
            try (GatewayLogProbe logs = new GatewayLogProbe(); var ignored = telemetry.makeCurrent()) {
                assertThat(client().exchange("success", correlation).block(LIMIT)).isEqualTo("outbound-token-sentinel");
                await().atMost(LIMIT).untilAsserted(() -> assertThat(logs.clients()).hasSize(1));
                var event = logs.clients().getFirst();
                assertThat(event.path("correlation_id").asString()).isEqualTo(correlation);
                assertThat(event.path("url").path("path").asString()).isEqualTo("/identity-prefix/internal/api/v1/token/exchange");
                assertThat(event.path("event").path("outcome").asString()).isEqualTo("success");
                assertThat(event.path("target").asString()).isEqualTo("identity-service");
                assertThat(event.path("http").path("response").path("body").path("bytes").asLong()).isPositive();
                assertThat(event.has("actor_id")).isFalse();
                assertThat(event.has("retry")).isFalse();
                assertThat(event.toString()).doesNotContain("outbound-query-sentinel", "outbound-token-sentinel");
                assertThat(logs.errors()).isEmpty();
                var receipts = RECEIPTS.stream().filter(receipt -> receipt.correlation().equals(correlation)).toList();
                assertThat(receipts).hasSize(1);
                if (agent) {
                    var receipt = receipts.getFirst();
                    assertThat(receipt.traceparent()).startsWith("00-" + trace + "-").endsWith("-" + flags);
                    assertThat(receipt.tracestate()).isEqualTo("fixture=retained");
                    assertThat(event.path("trace_id").asString()).isEqualTo(trace);
                    assertThat(event.path("span_id").asString()).matches("[0-9a-f]{16}");
                }
            }
        }
    }

    @Test
    void rejectionAndConnectionFailurePreserveClientErrorsWithoutRecoveryLogs() throws IOException {
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            assertThatThrownBy(() -> client().exchange("rejected", UUID.randomUUID().toString()).block(LIMIT))
                    .isInstanceOf(IdentityTokenExchangeClient.SessionRejectedException.class);
            assertThatThrownBy(() -> client().exchange("unavailable", UUID.randomUUID().toString()).block(LIMIT))
                    .isInstanceOf(IdentityTokenExchangeClient.IdentityUnavailableException.class);
            int closedPort;
            try (ServerSocket reservation = new ServerSocket(0)) {
                closedPort = reservation.getLocalPort();
            }
            IdentityTokenExchangeClient unavailable = new IdentityTokenExchangeClient(
                    WebClient.builder().baseUrl("http://127.0.0.1:" + closedPort).build(), "Basic fixture", Duration.ofMillis(500));
            assertThatThrownBy(() -> unavailable.exchange("success", UUID.randomUUID().toString()).block(LIMIT))
                    .isInstanceOf(IdentityTokenExchangeClient.IdentityUnavailableException.class);
            await().atMost(LIMIT).untilAsserted(() -> assertThat(logs.clients()).hasSize(3));
            assertThat(logs.clients()).allSatisfy(event -> {
                assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
                assertThat(event.path("log").path("level").asString()).isEqualTo("INFO");
                assertThat(event.path("error").has("stack_trace")).isFalse();
                assertThat(event.path("error").has("message")).isFalse();
                assertThat(event.has("retry")).isFalse();
            });
            assertThat(logs.clients().getFirst().path("http").path("response").path("status_code").asInt()).isEqualTo(401);
            assertThat(logs.clients().get(1).path("http").path("response").path("status_code").asInt()).isEqualTo(503);
            assertThat(logs.clients().getLast().path("http").has("response")).isFalse();
            assertThat(logs.errors()).isEmpty();
        }
    }

    @Test
    void bodyDeadlineAndCancellationDoNotReportHeadersAsCompletedSuccess() throws Exception {
        for (String action : List.of("deadline", "cancel")) {
            Block block = new Block(new CountDownLatch(1), new CountDownLatch(1));
            BLOCKS.put(action, block);
            try (GatewayLogProbe logs = new GatewayLogProbe()) {
                String correlation = UUID.randomUUID().toString();
                CountDownLatch received = new CountDownLatch(1);
                BODY_RECEIVED.put(correlation, received);
                var result = client().exchange(action, correlation).toFuture();
                assertThat(block.started().await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(logs.clients()).isEmpty();
                if (action.equals("cancel")) {
                    result.cancel(true);
                } else {
                    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(IdentityTokenExchangeClient.IdentityUnavailableException.class);
                }
                await().atMost(LIMIT).untilAsserted(() -> assertThat(logs.clients()).hasSize(1));
                var event = logs.clients().getFirst();
                assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
                assertThat(event.path("event").path("outcome").asString()).isEqualTo(action.equals("cancel") ? "unknown" : "failure");
                assertThat(event.path("error").has("stack_trace")).isFalse();
                assertThat(logs.errors()).isEmpty();
            } finally {
                block.release().countDown();
            }
        }
    }

    private static IdentityTokenExchangeClient client() {
        return new IdentityTokenExchangeClient(WebClient.builder()
                .baseUrl("http://localhost:" + PEER.getAddress().getPort() + "/identity-prefix?search=outbound-query-sentinel")
                .filter((request, next) -> next.exchange(request).map(response -> response.mutate()
                        .body(body -> body.doOnNext(buffer -> {
                            CountDownLatch received = BODY_RECEIVED.get(request.headers().getFirst("X-Correlation-Id"));
                            if (received != null) {
                                received.countDown();
                            }
                        })).build())).build(),
                "Basic fixture", Duration.ofMillis(500));
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", IdentityTokenExchangeClientIT::respond);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String action = JSON.readTree(exchange.getRequestBody().readAllBytes()).path("sessionToken").asString();
        RECEIPTS.add(new Receipt("identity-service", exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                exchange.getRequestHeaders().getFirst("traceparent"), exchange.getRequestHeaders().getFirst("tracestate")));
        assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("search=outbound-query-sentinel");
        int status = action.equals("rejected") ? 401 : action.equals("unavailable") ? 503 : 200;
        byte[] body = "{\"accessToken\":\"outbound-token-sentinel\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (exchange) {
            Block block = BLOCKS.get(action);
            if (block != null) {
                exchange.getResponseBody().write(body, 0, 1);
                exchange.getResponseBody().flush();
                block.started().countDown();
                try {
                    block.release().await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            exchange.getResponseBody().write(body);
        }
    }

    private record Receipt(String target, String path, String correlation, String traceparent, String tracestate) {}
    private record Block(CountDownLatch started, CountDownLatch release) {}

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Configuration {}
}
