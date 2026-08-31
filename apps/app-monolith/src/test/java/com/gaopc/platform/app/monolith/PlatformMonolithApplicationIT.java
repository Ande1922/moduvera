package com.gaopc.platform.app.monolith;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaopc.platform.catalog.api.CatalogApi;
import com.gaopc.platform.catalog.application.CatalogApplicationService;
import com.gaopc.platform.inventory.api.InventoryApi;
import com.gaopc.platform.inventory.application.InventoryApplicationService;
import com.gaopc.platform.message.Destination;
import com.gaopc.platform.message.MessageDescriptor;
import com.gaopc.platform.message.MessageId;
import com.gaopc.platform.message.MessageKind;
import com.gaopc.platform.message.MessageType;
import com.gaopc.platform.message.SerializedMessage;
import com.gaopc.platform.message.outbox.MessageTransport;
import com.gaopc.platform.message.outbox.OutboxWorker;
import com.gaopc.platform.order.api.OrderApi;
import com.gaopc.platform.order.application.OrderApplicationService;
import com.gaopc.platform.order.infrastructure.http.CatalogHttpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PlatformMonolithApplicationIT.TestSecurity.class)
class PlatformMonolithApplicationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));
    private static final String RESERVE_TOPIC = "monolith-reserve-" + UUID.randomUUID();
    private static final String RESULT_TOPIC = "monolith-result-" + UUID.randomUUID();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    @SuppressWarnings("deprecation")
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(
            System.getProperty("kafka.test.image", "confluentinc/cp-kafka:7.3.3")));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("platform.messaging.kafka.relay-enabled", () -> false);
        properties.add("platform.messaging.kafka.failure-backoff", () -> "0ms");
        properties.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
        properties.add("spring.cloud.stream.bindings.reserveInventory-out-0.destination", () -> RESERVE_TOPIC);
        properties.add("spring.cloud.stream.bindings.reserveInventory-in-0.destination", () -> RESERVE_TOPIC);
        properties.add("spring.cloud.stream.bindings.reserveInventory-in-0.group", () -> "monolith-inventory");
        properties.add("spring.cloud.stream.bindings.inventoryResult-out-0.destination", () -> RESULT_TOPIC);
        properties.add("spring.cloud.stream.bindings.inventoryResult-in-0.destination", () -> RESULT_TOPIC);
        properties.add("spring.cloud.stream.bindings.inventoryResult-in-0.group", () -> "monolith-order");
        configureSynchronousProducer(properties, "reserveInventory-out-0");
        configureSynchronousProducer(properties, "inventoryResult-out-0");
    }

    private static void configureSynchronousProducer(
            DynamicPropertyRegistry properties, String binding) {
        String prefix = "spring.cloud.stream.kafka.bindings." + binding + ".producer.";
        properties.add(prefix + "sync", () -> true);
        properties.add(prefix + "configuration.acks", () -> "all");
        properties.add(prefix + "configuration.delivery.timeout.ms", () -> "2000");
        properties.add(prefix + "configuration.request.timeout.ms", () -> "2000");
        properties.add(prefix + "configuration.max.block.ms", () -> "2000");
        properties.add(
                prefix + "configuration.key.serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        properties.add(
                prefix + "configuration.value.serializer",
                () -> "org.apache.kafka.common.serialization.ByteArraySerializer");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxWorker outbox;

    @Autowired
    private MessageTransport transport;

    @BeforeEach
    void cleanAndSeed() {
        jdbc.update("DELETE FROM platform_message_inbox");
        jdbc.update("DELETE FROM platform_message_outbox");
        jdbc.update("DELETE FROM inventory_reservation_result");
        jdbc.update("DELETE FROM inventory_stock");
        jdbc.update("DELETE FROM order_line");
        jdbc.update("DELETE FROM order_header");
        jdbc.update("DELETE FROM catalog_product");
        product("tenant-a", 100, "Keyboard", "399.00");
        product("tenant-a", 200, "Monitor", "899.00");
        stock("tenant-a", 100, 5);
        stock("tenant-a", 200, 1);
    }

    @Test
    @Order(1)
    void composesExactlyOneLocalBusinessApiAndPrefixesOnlyThePublicOrderController()
            throws Exception {
        assertThat(context.getBeansOfType(CatalogApi.class).values())
                .singleElement()
                .isInstanceOf(CatalogApplicationService.class);
        assertThat(context.getBeansOfType(OrderApi.class).values())
                .singleElement()
                .isInstanceOf(OrderApplicationService.class);
        assertThat(context.getBeansOfType(InventoryApi.class).values())
                .singleElement()
                .isInstanceOf(InventoryApplicationService.class);
        assertThat(context.getBeansOfType(CatalogHttpClient.class)).isEmpty();

        HttpResponse<String> created = postOrder(100, 1, "corr-prefix");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location)
                        .startsWith("/api/order/v1/orders/"));
        assertThat(post("/v1/orders", "{}", "corr-local-path").statusCode())
                .isEqualTo(404);
        assertThat(get("/internal/api/v1/catalog/products/100", "corr-catalog").statusCode())
                .isEqualTo(200);
        assertThat(get("/api/order/internal/api/v1/catalog/products/100", "corr-double")
                        .statusCode())
                .isEqualTo(404);
        assertThat(get("/api/order/actuator/health", "corr-actuator-prefixed")
                        .statusCode())
                .isEqualTo(404);
        assertThat(get("/actuator/health", "corr-actuator").statusCode()).isEqualTo(200);
        assertThat(count("flyway_history_catalog")).isPositive();
        assertThat(count("flyway_history_order")).isPositive();
        assertThat(count("flyway_history_inventory")).isPositive();
        assertThat(count("flyway_history_messaging")).isPositive();
    }

    @Test
    @Order(2)
    void confirmsRejectsAndDeduplicatesThroughTheRealKafkaOutboxInboxPath()
            throws Exception {
        String confirmed = createOrder(100, 2, "corr-confirm");
        publishNextOutboxMessage();
        eventually(() -> reservationCount(confirmed) == 1);
        publishNextOutboxMessage();
        eventuallyStatus(confirmed, "CONFIRMED");

        int inboxAfterConfirmation = count("platform_message_inbox");
        int stockAfterConfirmation = available(100);
        transport.send(reserveMessage(confirmed, 100, 2, "corr-confirm"));
        eventually(() -> count("platform_message_inbox") == inboxAfterConfirmation);
        assertThat(available(100)).isEqualTo(stockAfterConfirmation);
        assertThat(reservationCount(confirmed)).isEqualTo(1);

        String rejected = createOrder(200, 2, "corr-reject");
        publishNextOutboxMessage();
        eventually(() -> reservationCount(rejected) == 1);
        publishNextOutboxMessage();
        eventuallyStatus(rejected, "REJECTED");
        assertThat(available(200)).isEqualTo(1);

        assertThat(count("platform_message_inbox")).isEqualTo(inboxAfterConfirmation + 2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_message_outbox WHERE status = 'PUBLISHED'",
                        Integer.class))
                .isEqualTo(4);
    }

    @Test
    @Order(3)
    void leavesTheOutboxPendingWhileKafkaIsUnavailableAndCompletesAfterBrokerRecovery()
            throws Exception {
        String orderId = createOrder(100, 1, "corr-recovery");
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            var failed = outbox.publishBatch(1);
            assertThat(failed.failed()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM platform_message_outbox WHERE message_id = ?",
                            String.class,
                            "reserve-order-" + orderId))
                    .isEqualTo("PENDING");
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        eventually(() -> outbox.publishBatch(1).published() == 1);
        eventually(() -> reservationCount(orderId) == 1);
        publishNextOutboxMessage();
        eventuallyStatus(orderId, "CONFIRMED");
    }

    private String createOrder(long productId, int quantity, String correlation) throws Exception {
        HttpResponse<String> response = postOrder(productId, quantity, correlation);
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readTree(response.body()).get("orderId").asString();
    }

    private HttpResponse<String> postOrder(long productId, int quantity, String correlation)
            throws Exception {
        return post(
                "/api/order/v1/orders",
                "{\"lines\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}",
                correlation);
    }

    private HttpResponse<String> post(String path, String body, String correlation) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), correlation);
    }

    private HttpResponse<String> get(String path, String correlation) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET(), correlation);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String correlation) throws Exception {
        return HTTP.send(
                request.header("Authorization", "Bearer business-user")
                        .header("X-Correlation-Id", correlation)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void publishNextOutboxMessage() throws Exception {
        eventually(() -> outbox.publishBatch(1).published() == 1);
    }

    private void eventuallyStatus(String orderId, String status) throws Exception {
        eventually(() -> {
            try {
                HttpResponse<String> response = get(
                        "/api/order/v1/orders/" + orderId,
                        "corr-poll-" + status.toLowerCase(java.util.Locale.ROOT));
                return response.statusCode() == 200
                        && response.body().contains("\"status\":\"" + status + "\"");
            } catch (Exception failure) {
                return false;
            }
        });
    }

    private SerializedMessage reserveMessage(
            String orderId, long productId, int quantity, String correlation) throws Exception {
        String commandId = "reserve-order-" + orderId;
        var descriptor = new MessageDescriptor(
                new MessageId(commandId),
                MessageKind.ASYNC_COMMAND,
                new MessageType("com.gaopc.inventory.reserve.v1"),
                URI.create("urn:gaopc:order-service"),
                new Destination("inventory.reserve"),
                Instant.now(),
                new com.gaopc.platform.context.TenantId("tenant-a"),
                new com.gaopc.platform.context.Actor(
                        com.gaopc.platform.context.ActorType.SERVICE, "order-service"),
                correlation,
                null,
                new com.gaopc.platform.context.Initiator(
                        com.gaopc.platform.context.ActorType.USER, "alice"),
                orderId);
        String payload = "{\"commandId\":\"" + commandId + "\",\"orderId\":" + orderId
                + ",\"lines\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
        return SerializedMessage.json(descriptor, payload);
    }

    private int reservationCount(String orderId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation_result WHERE order_id = ?",
                Integer.class,
                Long.parseLong(orderId));
    }

    private int available(long productId) {
        return jdbc.queryForObject(
                "SELECT available FROM inventory_stock WHERE tenant_id = 'tenant-a' AND product_id = ?",
                Integer.class,
                productId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void product(String tenantId, long productId, String name, String price) {
        jdbc.update(
                """
                INSERT INTO catalog_product (
                    tenant_id, product_id, name, unit_price, currency, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), 'CNY', 0,
                    CURRENT_TIMESTAMP, 'test', CURRENT_TIMESTAMP, 'test')
                """,
                tenantId,
                productId,
                name,
                price);
    }

    private void stock(String tenantId, long productId, int available) {
        jdbc.update(
                """
                INSERT INTO inventory_stock (
                    tenant_id, product_id, available, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP, 'test', CURRENT_TIMESTAMP, 'test')
                """,
                tenantId,
                productId,
                available);
    }

    private static void eventually(java.util.function.BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plusSeconds(25);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition did not become true before timeout");
            }
            Thread.sleep(100);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSecurity {

        @Bean
        JwtDecoder monolithTestJwtDecoder() {
            return token -> {
                if (!"business-user".equals(token)) {
                    throw new JwtException("unknown test token");
                }
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "test")
                        .subject("alice")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(3600))
                        .claim("actor_type", "USER")
                        .claim("tenant_id", "tenant-a")
                        .claim(
                                "permissions",
                                List.of("catalog:read", "order:create", "order:read"))
                        .build();
            };
        }
    }
}
