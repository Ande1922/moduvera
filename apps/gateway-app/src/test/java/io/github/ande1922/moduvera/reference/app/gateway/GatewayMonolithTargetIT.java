package io.github.ande1922.moduvera.reference.app.gateway;

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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GatewayMonolithTargetIT {

    private static final String SESSION = "m".repeat(43);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicReference<String> LOGIN_PATH = new AtomicReference<>();
    private static final AtomicReference<String> ORDER_PATH = new AtomicReference<>();
    private static final HttpServer IDENTITY = identityServer();
    private static final HttpServer MONOLITH = monolithServer();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry properties) {
        properties.add(
                "gateway.identity-base-url",
                () -> "http://localhost:" + IDENTITY.getAddress().getPort());
        properties.add(
                "gateway.order-base-url",
                () -> "http://localhost:" + MONOLITH.getAddress().getPort());
        properties.add("gateway.order-target-preserves-prefix", () -> true);
        properties.add("gateway.service-secret", () -> "gateway-secret");
    }

    @LocalServerPort
    private int port;

    @AfterAll
    static void stopDownstreams() {
        IDENTITY.stop(0);
        MONOLITH.stop(0);
    }

    @Test
    void preservesTheOrderServicePrefixForTheMonolithButStillStripsIdentity() throws Exception {
        HttpResponse<String> login = post(
                "/api/identity/v1/session/login",
                null,
                "{\"username\":\"alice\",\"password\":\"secret\",\"tenantId\":\"tenant-a\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(LOGIN_PATH).hasValue("/v1/session/login");

        HttpResponse<String> created = post(
                "/api/order/v1/orders",
                "Bearer " + SESSION,
                "{\"lines\":[{\"productId\":100,\"quantity\":1}]}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(ORDER_PATH).hasValue("/api/order/v1/orders");
        assertThat(created.headers().firstValue("Location"))
                .contains("/api/order/v1/orders/42");
    }

    private HttpResponse<String> post(String path, String authorization, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Correlation-Id", "corr-monolith-target");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HTTP.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer identityServer() {
        HttpServer server = server();
        server.createContext("/v1/session/login", exchange -> {
            LOGIN_PATH.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"token\":\"" + SESSION + "\"}");
        });
        server.createContext("/internal/api/v1/token/exchange", exchange -> respond(
                exchange,
                200,
                "{\"accessToken\":\"internal-order-token\",\"tokenType\":\"Bearer\"}"));
        server.start();
        return server;
    }

    private static HttpServer monolithServer() {
        HttpServer server = server();
        server.createContext("/api/order/v1/orders", exchange -> {
            ORDER_PATH.set(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Location", "/api/order/v1/orders/42");
            respond(exchange, 201, "{\"orderId\":\"42\",\"status\":\"PENDING_STOCK\"}");
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("X-Correlation-Id", exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
