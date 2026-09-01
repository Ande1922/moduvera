package io.github.ande1922.moduvera.reference.app.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.messaging.kafka.migration.ModuveraMessagingMigrationConfiguration;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationMode;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationProperties;
import io.github.ande1922.moduvera.reference.order.OrderModuleConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.http.OrderHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging.InventoryResultInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.CatalogHttpClient;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.RemoteCatalogApiConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging.OutboxReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging.ReserveInventoryPublicationConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.OrderPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import io.github.ande1922.moduvera.reference.order.migration.OrderMigrationConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(OrderApplicationIT.TestOverrides.class)
class OrderApplicationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer CATALOG = catalogServer();
    private static final String RESERVE_TOPIC = "order-reserve-" + UUID.randomUUID();
    private static final String RESULT_TOPIC = "order-result-" + UUID.randomUUID();

    @Container
    @SuppressWarnings("deprecation")
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(
            System.getProperty("kafka.test.image", "confluentinc/cp-kafka:7.3.3")));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("moduvera.reference.clients.catalog.base-url", () -> "http://localhost:" + CATALOG.getAddress().getPort());
        properties.add("moduvera.reference.clients.identity.base-url", () -> "http://localhost:" + CATALOG.getAddress().getPort());
        properties.add("moduvera.reference.clients.identity.service-id", () -> "order-service");
        properties.add("moduvera.reference.clients.identity.service-secret", () -> "order-secret");
        properties.add("moduvera.messaging.kafka.relay-enabled", () -> false);
        properties.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
        properties.add("spring.cloud.stream.bindings.reserveInventory-out-0.destination", () -> RESERVE_TOPIC);
        properties.add("spring.cloud.stream.bindings.inventoryResult-in-0.destination", () -> RESULT_TOPIC);
        properties.add("spring.cloud.stream.bindings.inventoryResult-in-0.group", () -> "order-it");
        properties.add("spring.cloud.stream.bindings.inventoryResultTest-out-0.destination", () -> RESULT_TOPIC);
        properties.add(
                "spring.cloud.stream.bindings.inventoryResultTest-out-0.producer.use-native-encoding",
                () -> true);
        properties.add(
                "spring.cloud.stream.kafka.bindings.inventoryResultTest-out-0.producer.sync",
                () -> true);
        properties.add(
                "spring.cloud.stream.kafka.bindings.inventoryResultTest-out-0.producer.configuration.acks",
                () -> "all");
        properties.add(
                "spring.cloud.stream.kafka.bindings.inventoryResultTest-out-0.producer.configuration.delivery.timeout.ms",
                () -> "2000");
        properties.add(
                "spring.cloud.stream.kafka.bindings.inventoryResultTest-out-0.producer.configuration.request.timeout.ms",
                () -> "2000");
        properties.add(
                "spring.cloud.stream.kafka.bindings.inventoryResultTest-out-0.producer.configuration.max.block.ms",
                () -> "2000");
        properties.add(
                "spring.cloud.stream.kafka.bindings.inventoryResultTest-out-0.producer.configuration.key.serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        properties.add(
                "spring.cloud.stream.kafka.bindings.inventoryResultTest-out-0.producer.configuration.value.serializer",
                () -> "org.apache.kafka.common.serialization.ByteArraySerializer");
        properties.add(
                "moduvera.messaging.kafka.routes[inventory.reserve]",
                () -> "reserveInventory-out-0");
        properties.add(
                "moduvera.messaging.kafka.routes[order.inventory-result]",
                () -> "inventoryResultTest-out-0");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OutboxWorker outboxWorker;

    @Autowired
    private MessageTransport transport;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private List<MigrationDefinition> migrationDefinitions;

    @Autowired
    private ModuveraDatabaseMigrationProperties migrationProperties;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM moduvera_message_outbox");
        jdbc.update("DELETE FROM order_line");
        jdbc.update("DELETE FROM order_header");
    }

    @AfterAll
    static void stopCatalog() {
        CATALOG.stop(0);
    }

    @Test
    void createsAndReadsTenantIsolatedOrderWithDurableIntent() throws Exception {
        HttpResponse<String> created = post(100, 2, "order-user", "tenant-a", "corr-create");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).hasValueSatisfying(value -> assertThat(value)
                .startsWith("/v1/orders/"));
        String orderId = JSON.readTree(created.body()).get("orderId").asString();
        assertThat(created.body())
                .contains("\"orderId\":\"" + orderId + "\"")
                .contains("\"productId\":\"100\"")
                .contains("\"status\":\"PENDING_STOCK\"")
                .doesNotContain("\"data\"");
        assertThat(persistedOrder(orderId))
                .isEqualTo(new PersistedOrder("PENDING_STOCK", 0, "alice", "alice"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_header", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moduvera_message_outbox", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT convert_from(payload, 'UTF8') FROM moduvera_message_outbox", String.class))
                .contains("\"orderId\":" + orderId)
                .doesNotContain("tenant-a")
                .doesNotContain("tenantId");

        HttpResponse<String> found = get(orderId, "order-user", "tenant-a", "corr-get");
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.headers().firstValue("X-Correlation-Id")).contains("corr-get");
        assertThat(get(orderId, "other-tenant", "tenant-b", "corr-cross").statusCode())
                .isEqualTo(404);
        assertThat(get(orderId, null, "tenant-a", "corr-none").statusCode()).isEqualTo(401);
        assertThat(get(orderId, "no-permission", "tenant-a", "corr-forbidden").statusCode())
                .isEqualTo(403);
    }

    @Test
    void explicitlySelectsOnlyTheRemoteCatalogApi() {
        assertThat(applicationContext.getBeansOfType(CatalogApi.class).values())
                .singleElement()
                .isInstanceOf(CatalogHttpClient.class);
    }

    @Test
    void explicitlyComposesTheOrderRuntimeSlicesAndStartupMigrations() {
        assertThat(applicationContext.getBeansOfType(OrderModuleConfiguration.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(OrderHttpInboundConfiguration.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(InventoryResultInboundConfiguration.class))
                .hasSize(1);
        assertThat(applicationContext.getBeansOfType(RemoteCatalogApiConfiguration.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(OrderPersistenceConfiguration.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ReserveInventoryPublicationConfiguration.class))
                .hasSize(1);
        assertThat(applicationContext.getBeansOfType(OrderMigrationConfiguration.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ModuveraMessagingMigrationConfiguration.class))
                .hasSize(1);
        assertThat(migrationDefinitions)
                .extracting(definition -> definition.component().value())
                .containsExactlyInAnyOrder("order", "messaging");
        assertThat(migrationProperties.getMode()).isEqualTo(ModuveraDatabaseMigrationMode.STARTUP);
    }

    @Test
    void remoteAndOutboxFailuresLeaveNoPartialOrder() throws Exception {
        assertThat(post(999, 1, "order-user", "tenant-a", "corr-remote").statusCode()).isEqualTo(422);
        assertEmptyBusinessTables();

        assertThat(post(998, 1, "order-user", "tenant-a", "corr-outbox").statusCode()).isEqualTo(500);
        assertEmptyBusinessTables();
    }

    @Test
    void validatesInputAndFailsClosedBeforeSqlWithoutContext() throws Exception {
        assertThat(post(100, 0, "order-user", "tenant-a", "corr-invalid").statusCode()).isEqualTo(400);
        assertThatThrownBy(() -> orders.findById(1L)).isInstanceOf(MissingExecutionContextException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_history_order WHERE success", Integer.class))
                .isPositive();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_history_messaging WHERE success", Integer.class))
                .isPositive();
    }

    @Test
    void movesOrdersToBothTerminalStatesThroughRealKafkaAndDeduplicatesResults() throws Exception {
        String confirmedId = createOrder("corr-confirm-create");
        assertThat(outboxWorker.publishBatch(10).published()).isEqualTo(1);
        SerializedMessage confirmed = resultMessage(
                "result-confirmed-" + confirmedId,
                confirmedId,
                "{\"commandId\":\"reserve-order-" + confirmedId + "\",\"orderId\":"
                        + confirmedId + ",\"reservedAt\":\"2026-08-30T00:00:01Z\"}",
                "corr-confirm-result");
        transport.send(confirmed);
        eventuallyStatus(confirmedId, "CONFIRMED");
        transport.send(confirmed);
        eventually(() -> count("moduvera_message_inbox") == 1);

        String rejectedId = createOrder("corr-reject-create");
        assertThat(outboxWorker.publishBatch(10).published()).isEqualTo(1);
        transport.send(resultMessage(
                "result-rejected-" + rejectedId,
                rejectedId,
                "{\"commandId\":\"reserve-order-" + rejectedId + "\",\"orderId\":"
                        + rejectedId
                        + ",\"unavailableProductIds\":[100],\"rejectedAt\":\"2026-08-30T00:00:02Z\"}",
                "corr-reject-result"));
        eventuallyStatus(rejectedId, "REJECTED");

        assertThat(persistedOrder(confirmedId))
                .isEqualTo(new PersistedOrder("CONFIRMED", 1, "alice", "inventory-service"));
        assertThat(count("moduvera_message_inbox")).isEqualTo(2);
    }

    private PersistedOrder persistedOrder(String orderId) {
        return jdbc.queryForObject(
                """
                SELECT status, version, created_by, updated_by
                  FROM order_header
                 WHERE tenant_id = 'tenant-a'
                   AND order_id = ?
                """,
                (result, rowNumber) -> new PersistedOrder(
                        result.getString("status"),
                        result.getLong("version"),
                        result.getString("created_by"),
                        result.getString("updated_by")),
                Long.parseLong(orderId));
    }

    private String createOrder(String correlationId) throws Exception {
        HttpResponse<String> response = post(100, 1, "order-user", "tenant-a", correlationId);
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readTree(response.body()).get("orderId").asString();
    }

    private SerializedMessage resultMessage(
            String messageId, String orderId, String payload, String correlationId) {
        var descriptor = new MessageDescriptor(
                new MessageId(messageId),
                MessageKind.EVENT,
                new MessageType("io.github.ande1922.moduvera.reference.inventory.reservation-result.v1"),
                URI.create("urn:moduvera:reference:inventory-service"),
                new Destination("order.inventory-result"),
                Instant.parse("2026-08-30T00:00:02Z"),
                new TenantId("tenant-a"),
                new Actor(
                        ActorType.SERVICE,
                        "inventory-service",
                        Set.of("order:apply-inventory-result")),
                correlationId,
                new MessageId("reserve-order-" + orderId),
                new Initiator(ActorType.USER, "alice"),
                "tenant-a:" + orderId);
        return SerializedMessage.json(descriptor, payload);
    }

    private void eventuallyStatus(String orderId, String status) throws Exception {
        eventually(() -> {
            try {
                return get(orderId, "order-user", "tenant-a", "corr-poll-" + status)
                        .body()
                        .contains("\"status\":\"" + status + "\"");
            } catch (Exception failure) {
                return false;
            }
        });
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record PersistedOrder(String status, long version, String createdBy, String updatedBy) {}

    private static void eventually(java.util.function.BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition did not become true before timeout");
            }
            Thread.sleep(50);
        }
    }

    private void assertEmptyBusinessTables() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_header", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moduvera_message_outbox", Integer.class)).isZero();
    }

    private HttpResponse<String> post(
            long productId, int quantity, String token, String tenantId, String correlationId)
            throws Exception {
        String body = "{\"lines\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), token, tenantId, correlationId);
    }

    private HttpResponse<String> get(
            String orderId, String token, String tenantId, String correlationId) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/orders/" + orderId))
                .GET(), token, tenantId, correlationId);
    }

    private HttpResponse<String> send(
            HttpRequest.Builder request, String token, String tenantId, String correlationId)
            throws Exception {
        request.header("Tenant-Id", tenantId).header("X-Correlation-Id", correlationId);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer catalogServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/api/v1/catalog/products", OrderApplicationIT::catalogResponse);
            server.createContext("/internal/api/v1/service-token", OrderApplicationIT::serviceTokenResponse);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void catalogResponse(HttpExchange exchange) throws IOException {
        String productId = exchange.getRequestURI().getPath().replaceAll(".*/", "");
        boolean trusted = "Bearer catalog-service-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                && "tenant-a".equals(exchange.getRequestHeaders().getFirst("Tenant-Id"))
                && exchange.getRequestHeaders().getFirst("X-Correlation-Id") != null;
        if (!trusted || "999".equals(productId)) {
            respond(exchange, trusted ? 503 : 401, "{\"type\":\"about:blank\",\"status\":503}");
            return;
        }
        respond(
                exchange,
                200,
                "{\"productId\":" + productId
                        + ",\"name\":\"Keyboard\",\"unitPrice\":399.00,\"currency\":\"CNY\",\"version\":3}");
    }

    private static void serviceTokenResponse(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean trusted = basic("order-service", "order-secret").equals(authorization)
                && body.contains("\"tenantId\":\"tenant-a\"")
                && body.contains("\"audience\":\"catalog-service\"")
                && body.contains("\"initiatorType\":\"USER\"")
                && body.contains("\"initiatorId\":\"alice\"");
        respond(exchange, trusted ? 200 : 401,
                trusted ? "{\"accessToken\":\"catalog-service-token\"}" : "{\"status\":401}");
    }

    private static String basic(String serviceId, String secret) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (serviceId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestOverrides {

        @Bean
        JwtDecoder orderTestJwtDecoder() {
            return token -> switch (token) {
                case "order-user" -> user(token, "tenant-a", List.of("order:create", "order:read"));
                case "other-tenant" -> user(token, "tenant-b", List.of("order:create", "order:read"));
                case "no-permission" -> user(token, "tenant-a", List.of());
                default -> throw new JwtException("unknown test token");
            };
        }

        @Bean
        @Primary
        ReserveInventoryPublisher failingAfterAppendPublisher(
                DurablePublication outbox, ObjectMapper json, Clock clock) {
            var delegate = new OutboxReserveInventoryPublisher(outbox, json, clock);
            return command -> {
                delegate.publish(command);
                if (command.lines().stream().anyMatch(line -> line.productId() == 998L)) {
                    throw new IllegalStateException("injected failure after outbox append");
                }
            };
        }

        private static Jwt user(String token, String tenantId, List<String> permissions) {
            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "test")
                    .subject("alice")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .claim("actor_type", "USER")
                    .claim("tenant_id", tenantId)
                    .claim("permissions", permissions)
                    .build();
        }
    }
}
