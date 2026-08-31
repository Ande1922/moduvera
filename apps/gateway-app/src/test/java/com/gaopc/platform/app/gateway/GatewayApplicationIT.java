package com.gaopc.platform.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GatewayApplicationIT {

    private static final String SESSION = "a".repeat(43);
    private static final String EXPIRED_SESSION = "b".repeat(43);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> ORDER_TENANT = new AtomicReference<>();
    private static final AtomicReference<String> ORDER_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> ORDER_CORRELATION = new AtomicReference<>();
    private static final AtomicReference<String> ORDER_PATH = new AtomicReference<>();
    private static final AtomicReference<String> LOGIN_PATH = new AtomicReference<>();
    private static final AtomicReference<String> LOGIN_TENANT = new AtomicReference<>();
    private static final AtomicReference<String> LOGIN_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> EXCHANGE_CORRELATION = new AtomicReference<>();
    private static final HttpServer IDENTITY = identityServer();
    private static final HttpServer ORDER = orderServer();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry properties) {
        properties.add("gateway.identity-base-url", () -> "http://localhost:" + IDENTITY.getAddress().getPort());
        properties.add("gateway.order-base-url", () -> "http://localhost:" + ORDER.getAddress().getPort());
        properties.add("gateway.service-secret", () -> "gateway-secret");
    }

    @LocalServerPort
    private int port;

    @AfterAll
    static void stopDownstreams() {
        IDENTITY.stop(0);
        ORDER.stop(0);
    }

    @Test
    void exposesOnlyLoginAndVersionedOrderFlowWithoutLeakingInternalIdentityOrTenantHeader()
            throws Exception {
        HttpResponse<String> login = post(
                "/api/identity/v1/session/login",
                "Bearer browser-credential-must-not-propagate",
                "corr-login",
                """
                {"username":"alice","password":"secret","tenantId":"tenant-a"}
                """,
                "tenant-evil");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(login.body()).get("token").asString()).isEqualTo(SESSION);
        assertThat(login.body()).doesNotContain("internal-jwt-sensitive");

        HttpResponse<String> created = post(
                "/api/order/v1/orders",
                "Bearer " + SESSION,
                "corr-public-order",
                """
                {"lines":[{"productId":100,"quantity":1}]}
                """,
                "tenant-evil");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).contains("/api/order/v1/orders/9001");
        assertThat(created.headers().firstValue("X-Correlation-Id")).contains("corr-public-order");
        assertThat(created.headers().firstValue("X-Downstream-Probe")).contains("order");
        assertThat(created.body())
                .contains("\"orderId\":\"9001\"")
                .doesNotContain("\"data\"")
                .doesNotContain("internal-jwt-sensitive");
        assertThat(ORDER_AUTHORIZATION).hasValue("Bearer internal-jwt-sensitive");
        assertThat(ORDER_TENANT).hasValue(null);
        assertThat(ORDER_CORRELATION).hasValue("corr-public-order");
        assertThat(ORDER_PATH).hasValue("/v1/orders");
        assertThat(LOGIN_PATH).hasValue("/v1/session/login");
        assertThat(LOGIN_TENANT).hasValue(null);
        assertThat(LOGIN_AUTHORIZATION).hasValue(null);
        assertThat(EXCHANGE_CORRELATION).hasValue("corr-public-order");

        assertThat(get("/internal/api/v1/token/exchange", null, null).statusCode()).isEqualTo(404);
        assertThat(get("/internal/api/v1/catalog/products/100", null, null).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/orders/9001", "Bearer " + SESSION, null).statusCode())
                .isEqualTo(404);
        assertThat(get("/api/v1/session/login", null, null).statusCode()).isEqualTo(404);
        assertThat(get("/api/identity/v1/session/login", null, null).statusCode()).isEqualTo(404);
        assertThat(get("/api/identity/internal/api/v1/token/exchange", null, null).statusCode())
                .isEqualTo(404);
        assertThat(get("/api/order/internal/api/v1/catalog/products/100", null, null).statusCode())
                .isEqualTo(404);
        assertThat(get("/api/order/actuator/health", null, null).statusCode()).isEqualTo(404);
        assertThat(get("/api/catalog/v1/orders/9001", "Bearer " + SESSION, null).statusCode())
                .isEqualTo(404);
        assertThat(get("/api/order/v1/orders/9001", "Bearer " + SESSION, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void returnsStableAuthenticationAndValidationFailures() throws Exception {
        assertThat(post("/api/order/v1/orders", null, null, "{}", null).statusCode()).isEqualTo(401);
        assertThat(post("/api/order/v1/orders", "Bearer jwt.shaped.value", null, "{}", null)
                        .statusCode())
                .isEqualTo(401);
        assertThat(post("/api/order/v1/orders", "Bearer " + EXPIRED_SESSION, null, "{}", null)
                        .statusCode())
                .isEqualTo(401);
        HttpResponse<String> invalidId =
                get("/api/order/v1/orders/not-a-number", "Bearer " + SESSION, null);
        assertThat(invalidId.statusCode()).isEqualTo(400);
        assertThat(invalidId.body()).contains("\"code\":\"request.validation-failed\"");

        HttpResponse<String> generatedCorrelation =
                get("/api/order/v1/orders/9001", "Bearer " + SESSION, null);
        String correlation = generatedCorrelation.headers()
                .firstValue("X-Correlation-Id")
                .orElseThrow();
        assertThat(correlation).isNotBlank();
        assertThat(ORDER_CORRELATION).hasValue(correlation);
    }

    private HttpResponse<String> post(
            String path, String authorization, String correlation, String body, String tenant)
            throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        headers(request, authorization, correlation, tenant);
        return HTTP.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String authorization, String correlation) throws Exception {
        var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
        headers(request, authorization, correlation, null);
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void headers(
            HttpRequest.Builder request, String authorization, String correlation, String tenant) {
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (correlation != null) {
            request.header("X-Correlation-Id", correlation);
        }
        if (tenant != null) {
            request.header("Tenant-Id", tenant);
        }
    }

    private static HttpServer identityServer() {
        HttpServer server = server();
        server.createContext("/v1/session/login", exchange -> {
            LOGIN_PATH.set(exchange.getRequestURI().getPath());
            LOGIN_TENANT.set(exchange.getRequestHeaders().getFirst("Tenant-Id"));
            LOGIN_AUTHORIZATION.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            send(
                    exchange,
                    200,
                    "application/json",
                    "{\"token\":\"" + SESSION + "\",\"expiresAt\":\"2026-08-31T00:00:00Z\"}");
        });
        server.createContext("/internal/api/v1/token/exchange", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            EXCHANGE_CORRELATION.set(
                    exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            boolean trusted = exchange.getRequestHeaders()
                    .getFirst("Authorization")
                    .equals("Basic Z2F0ZXdheTpnYXRld2F5LXNlY3JldA==");
            if (!trusted || body.contains(EXPIRED_SESSION)) {
                send(exchange, 401, "application/problem+json", "{\"code\":\"identity.invalid-session\"}");
                return;
            }
            send(
                    exchange,
                    200,
                    "application/json",
                    "{\"accessToken\":\"internal-jwt-sensitive\",\"tokenType\":\"Bearer\"}");
        });
        server.start();
        return server;
    }

    private static HttpServer orderServer() {
        HttpServer server = server();
        server.createContext("/v1/orders", exchange -> {
            ORDER_TENANT.set(exchange.getRequestHeaders().getFirst("Tenant-Id"));
            ORDER_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ORDER_CORRELATION.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            ORDER_PATH.set(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("X-Downstream-Probe", "order");
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Location", "/v1/orders/9001");
                send(
                        exchange,
                        201,
                        "application/json",
                        "{\"orderId\":\"9001\",\"status\":\"PENDING_STOCK\"}");
            } else if (exchange.getRequestURI().getPath().endsWith("/not-a-number")) {
                send(
                        exchange,
                        400,
                        "application/problem+json",
                        "{\"code\":\"request.validation-failed\"}");
            } else {
                send(
                        exchange,
                        200,
                        "application/json",
                        "{\"orderId\":\"9001\",\"status\":\"CONFIRMED\"}");
            }
        });
        server.start();
        return server;
    }

    private static HttpServer server() {
        try {
            return HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
