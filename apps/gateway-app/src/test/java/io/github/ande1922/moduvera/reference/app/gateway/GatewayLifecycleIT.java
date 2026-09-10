package io.github.ande1922.moduvera.reference.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(GatewayLifecycleIT.Controls.class)
class GatewayLifecycleIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentHashMap<String, Sinks.Empty<Void>> PENDING = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("gateway.service-secret", () -> UUID.randomUUID().toString());
    }

    @LocalServerPort
    private int port;

    @Test
    void actualClientResetAfterHeadersProducesOneNonSuccessResult() throws Exception {
        String path = "/lifecycle/pending/" + UUID.randomUUID();
        Sinks.Empty<Void> body = Sinks.empty();
        PENDING.put(path, body);
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            try (Socket socket = new Socket("localhost", port);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5000);
                socket.setSoLinger(true, 0);
                socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertThat(reader.readLine()).contains("200");
                String line;
                while ((line = reader.readLine()) != null && !line.equals("prefix")) {
                    // Wait for the first flushed body chunk, not merely beforeCommit.
                }
                assertThat(line).isEqualTo("prefix");
                assertThat(logs.canonical()).isEmpty();
            }
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical()).hasSize(1));
            var result = logs.canonical().getFirst();
            assertThat(result.path("url").path("path").asString()).isEqualTo(path);
            assertThat(result.path("event").path("outcome").asString()).isIn("failure", "unknown");
            assertThat(result.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
            body.tryEmitEmpty();
            assertThat(logs.canonical()).hasSize(1);
            assertThat(logs.errors()).isEmpty();
        } finally {
            PENDING.remove(path);
            body.tryEmitEmpty();
        }
    }

    @Test
    void unexpectedFailureAfterFlushedBodyAbortsSafelyWithOneFinalError() throws Exception {
        String path = "/lifecycle/pending/" + UUID.randomUUID();
        String payload = "postcommit-payload-" + UUID.randomUUID();
        String query = "postcommit-query-" + UUID.randomUUID();
        Sinks.Empty<Void> body = Sinks.empty();
        PENDING.put(path, body);
        try (GatewayLogProbe logs = new GatewayLogProbe();
                Socket socket = new Socket("localhost", port);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + path + "?search=" + query + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(reader.readLine()).contains("200");
            String line;
            String correlation = null;
            String trace = null;
            while ((line = reader.readLine()) != null && !line.equals("prefix")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("x-correlation-id:")) {
                    correlation = line.substring(line.indexOf(':') + 1).trim();
                }
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("x-trace-id:")) {
                    trace = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            assertThat(line).isEqualTo("prefix");
            assertThat(logs.canonical()).isEmpty();
            body.tryEmitError(new IllegalStateException("publisher failed " + payload));
            StringBuilder remaining = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                remaining.append(line);
            }
            // No terminating chunk or replacement Problem: the partial response is aborted.
            assertThat(remaining).isEmpty();
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical()).hasSize(1));
            var completion = logs.canonical().getFirst();
            assertThat(completion.path("correlation_id").asString()).isEqualTo(correlation);
            assertThat(completion.path("event").path("outcome").asString()).isEqualTo("failure");
            assertThat(completion.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
            assertThat(completion.path("client").path("ip").asString()).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
            assertThat(completion.has("user_agent")).isFalse();
            assertThat(logs.errors()).hasSize(1);
            var error = logs.errors().getFirst();
            assertThat(error.path("error").path("code").asString()).isEqualTo("SYS_UNEXPECTED");
            assertThat(error.path("correlation_id").asString()).isEqualTo(correlation);
            assertThat(error.toString()).doesNotContain(payload, query);
            assertThat(completion.toString()).doesNotContain(payload, query);
            if (Boolean.getBoolean("moduvera.test.agent")) {
                assertThat(error.path("trace_id").asString()).isEqualTo(trace);
                assertThat(error.path("span_id").asString()).isEqualTo(completion.path("span_id").asString());
            }
        } finally {
            PENDING.remove(path);
            body.tryEmitEmpty();
        }
    }

    @Test
    void actualClientMetadataUsesPeerAddressAndSafelyFormatsUserAgent() throws Exception {
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            for (String userAgent : List.of("gateway-fixture/1.0", "gateway-fixture credential=useragent-sensitive-value")) {
                var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/lifecycle/scheduled/metadata"))
                        .header("User-Agent", userAgent).header("X-Forwarded-For", "203.0.113.77")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                String correlation = response.headers().firstValue(GatewayRequestDiagnostics.HEADER).orElseThrow();
                await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical())
                        .anySatisfy(event -> assertThat(event.path("correlation_id").asString()).isEqualTo(correlation)));
                var completion = logs.canonical().stream()
                        .filter(event -> event.path("correlation_id").asString().equals(correlation)).findFirst().orElseThrow();
                assertThat(completion.path("client").path("ip").asString()).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
                assertThat(completion.path("user_agent").path("original").asString()).startsWith("gateway-fixture");
                assertThat(completion.toString()).doesNotContain("useragent-sensitive-value", "203.0.113.77");
                if (!userAgent.contains("credential")) {
                    assertThat(completion.path("user_agent").path("original").asString()).isEqualTo(userAgent);
                }
            }
        }
    }

    @Test
    void actualReactorResponsesRetainIndependentPublicCorrelationAcrossSchedulersAndErrors() throws Exception {
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            var first = HTTP.sendAsync(request("/lifecycle/scheduled/first"), HttpResponse.BodyHandlers.ofString());
            var second = HTTP.sendAsync(request("/lifecycle/scheduled/second"), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> one = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            HttpResponse<String> two = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(one.statusCode()).isEqualTo(200);
            assertThat(two.statusCode()).isEqualTo(200);
            String firstCorrelation = one.headers().firstValue(GatewayRequestDiagnostics.HEADER).orElseThrow();
            String secondCorrelation = two.headers().firstValue(GatewayRequestDiagnostics.HEADER).orElseThrow();
            assertThat(firstCorrelation).isNotEqualTo(secondCorrelation).isNotEqualTo("untrusted-correlation");
            assertThat(one.body()).isEqualTo(firstCorrelation);
            assertThat(two.body()).isEqualTo(secondCorrelation);

            var forbidden = HTTP.send(request("/lifecycle/forbidden"), HttpResponse.BodyHandlers.ofString());
            assertThat(forbidden.statusCode()).isEqualTo(403);
            assertThat(JSON.readTree(forbidden.body()).path("correlationId").asString())
                    .isEqualTo(forbidden.headers().firstValue(GatewayRequestDiagnostics.HEADER).orElseThrow());
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical()).hasSize(3));
            assertThat(logs.canonical()).allSatisfy(event -> {
                assertThat(event.has("tenant_id")).isFalse();
                assertThat(event.has("actor_id")).isFalse();
                assertThat(event.has("user_id")).isFalse();
                assertThat(event.path("duration_ms").asDouble()).isGreaterThanOrEqualTo(0);
            });
            assertThat(logs.errors()).isEmpty();
        }
    }

    @Test
    void actualUnexpectedFailureHasOneSafeFinalErrorWithMatchingRequestContext() throws Exception {
        String sensitive = "sensitive-" + UUID.randomUUID();
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            var response = HTTP.send(request("/lifecycle/error?credential=" + sensitive), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(500);
            String correlation = response.headers().firstValue(GatewayRequestDiagnostics.HEADER).orElseThrow();
            assertThat(JSON.readTree(response.body()).path("correlationId").asString()).isEqualTo(correlation);
            assertThat(JSON.readTree(response.body()).path("code").asString()).isEqualTo("system.unexpected");
            assertThat(response.body()).doesNotContain(sensitive);
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical()).hasSize(1));
            assertThat(logs.errors()).hasSize(1);
            var error = logs.errors().getFirst();
            assertThat(error.path("correlation_id").asString()).isEqualTo(correlation);
            assertThat(error.path("error").path("code").asString()).isEqualTo("SYS_UNEXPECTED");
            assertThat(error.path("error").path("stack_trace").asString()).isNotBlank();
            assertThat(error.toString()).doesNotContain(sensitive);
            assertThat(error.has("duration_ms")).isFalse();
            assertThat(logs.canonical().getFirst().path("url").path("path").asString()).isEqualTo("/lifecycle/error");
            assertThat(logs.canonical().getFirst().path("event").path("outcome").asString()).isEqualTo("failure");
        }
    }

    @Test
    void agentServerContextMatchesResponseAndCanonicalForSampledUnsampledAndInvalidParents() throws Exception {
        // The same HTTP cases also run without an Agent; the locked-Agent command enables strict Trace assertions.
        boolean agent = Boolean.getBoolean("moduvera.test.agent");
        String parentTrace = "abcdef0123456789abcdef0123456789";
        String parentSpan = "abcdef0123456789";
        List<String> parents = List.of("00-" + parentTrace + "-" + parentSpan + "-01",
                "00-" + parentTrace + "-" + parentSpan + "-00", "invalid-trace-parent");
        try (GatewayLogProbe logs = new GatewayLogProbe()) {
            for (int index = 0; index < parents.size(); index++) {
                String path = "/lifecycle/scheduled/trace-" + index;
                var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .header("traceparent", parents.get(index))
                                .header("baggage", "tenant_id=untrusted-tenant,actor_id=untrusted-actor")
                                .GET().build(), HttpResponse.BodyHandlers.ofString());
                String correlation = response.headers().firstValue(GatewayRequestDiagnostics.HEADER).orElseThrow();
                int completed = index + 1;
                await().atMost(TIMEOUT).untilAsserted(() -> assertThat(logs.canonical()).hasSize(completed));
                var event = logs.canonical().stream()
                        .filter(value -> value.path("correlation_id").asString().equals(correlation)).findFirst().orElseThrow();
                assertThat(event.has("tenant_id")).isFalse();
                assertThat(event.has("actor_id")).isFalse();
                if (agent) {
                    String trace = response.headers().firstValue(GatewayRequestDiagnostics.TRACE_HEADER).orElseThrow();
                    assertThat(trace).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
                    assertThat(event.path("trace_id").asString()).isEqualTo(trace);
                    assertThat(event.path("span_id").asString()).matches("[0-9a-f]{16}").isNotEqualTo(parentSpan);
                    if (index < 2) {
                        assertThat(trace).isEqualTo(parentTrace);
                    } else {
                        assertThat(trace).isNotEqualTo(parentTrace);
                    }
                }
            }
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Correlation-Id", "untrusted-correlation")
                .header("Tenant-Id", "same-untrusted-tenant")
                .GET().build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Controls {

        @Bean
        WebFilter lifecycleFixture() {
            return (exchange, chain) -> {
                String path = exchange.getRequest().getPath().value();
                if (path.startsWith("/lifecycle/pending/")) {
                    return exchange.getResponse().writeAndFlushWith(Flux.just(
                            Mono.just(exchange.getResponse().bufferFactory().wrap("prefix\n".getBytes(StandardCharsets.UTF_8))),
                            PENDING.get(path).asMono().then(Mono.empty())));
                }
                if (path.startsWith("/lifecycle/scheduled/")) {
                    return Mono.deferContextual(context -> {
                        GatewayRequestDiagnostics state = context.get(GatewayRequestDiagnostics.class);
                        assertThat(state).isSameAs(GatewayRequestDiagnostics.find(exchange));
                        return Mono.just(state.correlationId()).publishOn(Schedulers.parallel())
                                .flatMap(correlation -> exchange.getResponse().writeWith(Mono.just(
                                        exchange.getResponse().bufferFactory().wrap(correlation.getBytes(StandardCharsets.UTF_8)))));
                    });
                }
                if (path.equals("/lifecycle/forbidden")) {
                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                }
                if (path.equals("/lifecycle/error")) {
                    return Mono.error(new IllegalStateException("request failed " + exchange.getRequest().getURI()));
                }
                return chain.filter(exchange);
            };
        }
    }
}
