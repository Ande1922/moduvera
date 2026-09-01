package io.github.ande1922.moduvera.reference.app.monolith;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.catalog.CatalogModuleConfiguration;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogInternalHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence.CatalogPersistenceOutboundConfiguration;
import io.github.ande1922.moduvera.reference.catalog.catalog.application.CatalogApplicationService;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.ProductRepository;
import io.github.ande1922.moduvera.reference.catalog.migration.CatalogMigrationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.InventoryModuleConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging.InventoryResultPublicationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.InventoryPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.migration.InventoryMigrationConfiguration;
import io.github.ande1922.moduvera.reference.order.OrderModuleConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.http.OrderHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging.InventoryResultInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.CatalogHttpClient;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.InternalAccessTokenProvider;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.RemoteCatalogApiConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging.ReserveInventoryPublicationConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.OrderPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.order.api.OrderApi;
import io.github.ande1922.moduvera.reference.order.application.OrderApplicationService;
import io.github.ande1922.moduvera.reference.order.application.ReserveInventoryPublisher;
import io.github.ande1922.moduvera.reference.order.domain.OrderRepository;
import io.github.ande1922.moduvera.reference.order.migration.OrderMigrationConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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
@Import(ModuveraMonolithApplicationIT.TestSecurity.class)
class ModuveraMonolithApplicationIT {

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
        properties.add("moduvera.messaging.kafka.relay-enabled", () -> false);
        properties.add("moduvera.messaging.kafka.failure-backoff", () -> "0ms");
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
        jdbc.update("DELETE FROM moduvera_message_inbox");
        jdbc.update("DELETE FROM moduvera_message_outbox");
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
    void composesLocalCatalogAndOrderApisWithTheAsynchronousInventoryInboundAdapter()
            throws Exception {
        assertThat(context.getBeansOfType(CatalogModuleConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(CatalogPersistenceOutboundConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(CatalogInternalHttpInboundConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(CatalogMigrationConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(OrderModuleConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(OrderPersistenceConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReserveInventoryPublicationConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(OrderHttpInboundConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryResultInboundConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(OrderMigrationConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryModuleConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryPersistenceConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryResultPublicationConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryMigrationConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(CatalogApi.class).values())
                .singleElement()
                .isInstanceOf(CatalogApplicationService.class);
        assertThat(context.getBeansOfType(OrderApi.class).values())
                .singleElement()
                .isInstanceOf(OrderApplicationService.class);
        assertThat(context.getBeansOfType(InventoryApplicationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(ProductRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(OrderRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryStore.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReserveInventoryPublisher.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryResultPublisher.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReserveInventoryCommandInboundConfiguration.class))
                .hasSize(1);
        assertThat(context.getBean("reserveInventory")).isInstanceOf(Consumer.class);
        assertThat(context.getBean("inventoryResult")).isInstanceOf(Consumer.class);
        assertThat(context.getBeansOfType(CatalogHttpClient.class)).isEmpty();
        assertThat(context.getBeansOfType(InternalAccessTokenProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(RemoteCatalogApiConfiguration.class)).isEmpty();
        assertThat(context.getBeansOfType(MigrationDefinition.class).values())
                .extracting(definition -> definition.component().value())
                .containsExactlyInAnyOrder("catalog", "order", "inventory", "messaging");

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
        assertThat(jdbc.queryForList(
                        "SELECT component_name FROM moduvera_database_components ORDER BY component_name",
                        String.class))
                .containsExactly("catalog", "inventory", "messaging", "order");
        assertThat(migrationScripts("catalog")).containsExactly("V1__create_catalog.sql");
        assertThat(migrationScripts("order")).containsExactly("V1__create_order.sql");
        assertThat(migrationScripts("inventory")).containsExactly("V1__create_inventory.sql");
        assertThat(migrationScripts("messaging"))
                .containsExactly("V1__create_moduvera_messaging.sql");
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

        int inboxAfterConfirmation = count("moduvera_message_inbox");
        int outboxAfterConfirmation = count("moduvera_message_outbox");
        int stockAfterConfirmation = available(100);
        String commandId = "reserve-order-" + confirmed;
        String replayBarrierId = "replay-barrier-" + confirmed;
        transport.send(reserveMessage(confirmed, 100, 2, "corr-confirm"));
        transport.send(reserveMessage(
                replayBarrierId, commandId, confirmed, 100, 2, "corr-confirm"));
        eventually(() -> inboxMessageCount(replayBarrierId) == 1);
        assertThat(available(100)).isEqualTo(stockAfterConfirmation);
        assertThat(reservationCount(confirmed)).isEqualTo(1);
        assertThat(count("moduvera_message_outbox")).isEqualTo(outboxAfterConfirmation);

        String rejected = createOrder(200, 2, "corr-reject");
        publishNextOutboxMessage();
        eventually(() -> reservationCount(rejected) == 1);
        publishNextOutboxMessage();
        eventuallyStatus(rejected, "REJECTED");
        assertThat(available(200)).isEqualTo(1);

        assertThat(count("moduvera_message_inbox")).isEqualTo(inboxAfterConfirmation + 3);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM moduvera_message_outbox WHERE status = 'PUBLISHED'",
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
                            "SELECT status FROM moduvera_message_outbox WHERE message_id = ?",
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
        return reserveMessage(commandId, commandId, orderId, productId, quantity, correlation);
    }

    private SerializedMessage reserveMessage(
            String messageId,
            String commandId,
            String orderId,
            long productId,
            int quantity,
            String correlation)
            throws Exception {
        var descriptor = new MessageDescriptor(
                new MessageId(messageId),
                MessageKind.valueOf(ReserveInventoryCommand.MESSAGE_KIND),
                new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:order-service"),
                new Destination(ReserveInventoryCommand.DESTINATION),
                Instant.now(),
                new io.github.ande1922.moduvera.context.TenantId("tenant-a"),
                new io.github.ande1922.moduvera.context.Actor(
                        io.github.ande1922.moduvera.context.ActorType.SERVICE, "order-service"),
                correlation,
                null,
                new io.github.ande1922.moduvera.context.Initiator(
                        io.github.ande1922.moduvera.context.ActorType.USER, "alice"),
                orderId);
        String payload = JSON.writeValueAsString(new ReserveInventoryCommand(
                commandId,
                Long.parseLong(orderId),
                List.of(new ReserveInventoryLine(productId, quantity))));
        return SerializedMessage.json(descriptor, payload);
    }

    private int reservationCount(String orderId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation_result WHERE order_id = ?",
                Integer.class,
                Long.parseLong(orderId));
    }

    private int inboxMessageCount(String messageId) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM moduvera_message_inbox
                 WHERE tenant_id = 'tenant-a'
                   AND consumer_id = 'inventory-reservation'
                   AND message_id = ?
                """,
                Integer.class,
                messageId);
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

    private List<String> migrationScripts(String component) {
        return jdbc.queryForList(
                "SELECT script FROM flyway_history_" + component
                        + " WHERE type = 'SQL' AND success ORDER BY installed_rank",
                String.class);
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
