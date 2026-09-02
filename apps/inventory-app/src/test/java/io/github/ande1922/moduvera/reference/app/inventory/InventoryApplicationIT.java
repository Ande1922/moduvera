package io.github.ande1922.moduvera.reference.app.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryLine;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging.OutboxInventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.InventoryMapper;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.testing.ProgressBarrier;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.migration.ModuveraMessagingMigrationConfiguration;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationAutoConfiguration;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationMode;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationProperties;
import io.github.ande1922.moduvera.reference.inventory.InventoryModuleConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging.InventoryResultPublicationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.InventoryPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.inventory.migration.InventoryMigrationConfiguration;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@Import(InventoryApplicationIT.TestOverrides.class)
class InventoryApplicationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    private static final String RESERVE_TOPIC = "inventory-reserve-" + UUID.randomUUID();
    private static final String RESULT_TOPIC = "inventory-result-" + UUID.randomUUID();

    @Container
    @SuppressWarnings("deprecation")
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(
            System.getProperty("kafka.test.image", "confluentinc/cp-kafka:7.3.3")));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        initializeDisposableDatabase();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("moduvera.messaging.kafka.relay-enabled", () -> true);
        properties.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
        properties.add("spring.cloud.stream.bindings.reserveInventory-in-0.destination", () -> RESERVE_TOPIC);
        properties.add("spring.cloud.stream.bindings.reserveInventory-in-0.group", () -> "inventory-it");
        properties.add("spring.cloud.stream.bindings.inventoryResult-out-0.destination", () -> RESULT_TOPIC);
        properties.add("spring.cloud.stream.bindings.reserveInventoryTest-out-0.destination", () -> RESERVE_TOPIC);
        properties.add(
                "spring.cloud.stream.bindings.reserveInventoryTest-out-0.producer.use-native-encoding",
                () -> true);
        properties.add(
                "spring.cloud.stream.kafka.bindings.reserveInventoryTest-out-0.producer.sync",
                () -> true);
        properties.add(
                "spring.cloud.stream.kafka.bindings.reserveInventoryTest-out-0.producer.configuration.acks",
                () -> "all");
        properties.add(
                "spring.cloud.stream.kafka.bindings.reserveInventoryTest-out-0.producer.configuration.delivery.timeout.ms",
                () -> "2000");
        properties.add(
                "spring.cloud.stream.kafka.bindings.reserveInventoryTest-out-0.producer.configuration.request.timeout.ms",
                () -> "2000");
        properties.add(
                "spring.cloud.stream.kafka.bindings.reserveInventoryTest-out-0.producer.configuration.max.block.ms",
                () -> "2000");
        properties.add(
                "spring.cloud.stream.kafka.bindings.reserveInventoryTest-out-0.producer.configuration.key.serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        properties.add(
                "spring.cloud.stream.kafka.bindings.reserveInventoryTest-out-0.producer.configuration.value.serializer",
                () -> "org.apache.kafka.common.serialization.ByteArraySerializer");
        properties.add(
                "moduvera.messaging.kafka.routes[inventory.reserve]",
                () -> "reserveInventoryTest-out-0");
    }

    private static void initializeDisposableDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ModuveraDatabaseMigrationAutoConfiguration.class))
                .withPropertyValues(
                        "moduvera.database.migration.mode=startup",
                        "moduvera.database.migration.initialize=true")
                .withBean(DataSource.class, () -> dataSource)
                .withUserConfiguration(
                        InventoryMigrationConfiguration.class,
                        ModuveraMessagingMigrationConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ModuveraDatabaseMigrationProperties migrationProperties;

    @Autowired
    private KafkaMessageMapper messages;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private MessageTransport transport;

    @Autowired
    @Qualifier("reserveInventory")
    private Consumer<Message<byte[]>> consumer;

    @Autowired
    private AtomicReference<ExecutionContext> observedContext;

    @BeforeEach
    void cleanAndSeed() {
        observedContext.set(null);
        jdbc.update("DELETE FROM moduvera_message_outbox");
        jdbc.update("DELETE FROM moduvera_message_inbox");
        jdbc.update("DELETE FROM inventory_reservation_result");
        jdbc.update("DELETE FROM inventory_stock");
        stock("tenant-a", 7, 5);
        stock("tenant-a", 8, 4);
        stock("tenant-b", 7, 100);
    }

    @Test
    void explicitlyComposesTheInventoryRuntimeSlicesAndMigrationDefinitions() {
        assertThat(context.getBeansOfType(InventoryModuleConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReserveInventoryCommandInboundConfiguration.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(InventoryPersistenceConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryResultPublicationConfiguration.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(InventoryMigrationConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(ModuveraMessagingMigrationConfiguration.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(MigrationDefinition.class).values())
                .extracting(definition -> definition.component().value())
                .containsExactlyInAnyOrder("inventory", "messaging");
        assertThat(migrationProperties.getMode()).isEqualTo(ModuveraDatabaseMigrationMode.VALIDATE);
        assertThat(migrationProperties.isInitialize()).isFalse();
    }

    @Test
    void reservesMultipleRowsOnceAndPublishesOneResultThroughKafka() throws Exception {
        ReserveInventoryCommand command = command(
                "reserve-order-42",
                42,
                List.of(new ReserveInventoryLine(8, 3), new ReserveInventoryLine(7, 2)));
        SerializedMessage message = serialized(command, "tenant-a", "corr-success");
        ProgressBarrier consumed = ProgressBarrier.capture(this::consumedReserveRecords);
        assertThat(message.descriptor().actor().permissions())
                .containsExactlyInAnyOrder("inventory:admin", "catalog:write");

        transport.send(message);
        eventually(() -> count("inventory_reservation_result") == 1);
        transport.send(message);
        consumed.awaitAdvanceBy(
                2,
                Duration.ofSeconds(15),
                Duration.ofMillis(50),
                () -> "consumer=inventory-it, topic=" + RESERVE_TOPIC);

        List<SerializedMessage> results = kafkaResults("inventory-result:reserve-order-42");

        assertThat(available("tenant-a", 7)).isEqualTo(3);
        assertThat(available("tenant-a", 8)).isEqualTo(1);
        assertThat(available("tenant-b", 7)).isEqualTo(100);
        assertThat(count("inventory_reservation_result")).isEqualTo(1);
        assertThat(count("moduvera_message_inbox")).isEqualTo(1);
        assertThat(count("moduvera_message_outbox")).isEqualTo(1);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().descriptor().kind()).isEqualTo(MessageKind.EVENT);
        assertThat(results.getFirst().descriptor().type())
                .isEqualTo(new MessageType(InventoryReservationResult.MESSAGE_TYPE));
        assertThat(results.getFirst().descriptor().source())
                .isEqualTo(URI.create("urn:moduvera:reference:inventory-service"));
        assertThat(results.getFirst().descriptor().destination())
                .isEqualTo(new Destination(InventoryReservationResult.DESTINATION));
        assertThat(results.getFirst().descriptor().actor())
                .isEqualTo(new Actor(ActorType.SERVICE, "inventory-service"));
        assertThat(results.getFirst().descriptor().tenantId()).isEqualTo(new TenantId("tenant-a"));
        assertThat(results.getFirst().descriptor().correlationId()).isEqualTo("corr-success");
        assertThat(results.getFirst().descriptor().initiator())
                .isEqualTo(new Initiator(ActorType.USER, "alice"));
        assertThat(json.readTree(results.getFirst().payload()).required("commandId").asString())
                .isEqualTo("reserve-order-42");
        assertThat(observedContext.get())
                .isEqualTo(new ExecutionContext(
                        new TenantId("tenant-a"),
                        new Actor(
                                ActorType.SERVICE,
                                "order-service",
                                Set.of("inventory:reserve")),
                        new Initiator(ActorType.USER, "alice"),
                        "corr-success"));
        assertThat(jdbc.queryForObject(
                        "SELECT result_type FROM inventory_reservation_result", String.class))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject(
                        "SELECT partition_key FROM moduvera_message_outbox", String.class))
                .isEqualTo("tenant-a:42");
    }

    @Test
    void insufficientLineThroughKafkaLeavesEveryStockRowUntouchedAndPublishesRejected()
            throws Exception {
        ReserveInventoryCommand command = command(
                "reserve-order-43",
                43,
                List.of(new ReserveInventoryLine(7, 2), new ReserveInventoryLine(8, 5)));

        transport.send(serialized(command, "tenant-a", "corr-rejected"));
        eventually(() -> count("inventory_reservation_result") == 1);
        List<SerializedMessage> results = kafkaResults("inventory-result:reserve-order-43");

        assertThat(available("tenant-a", 7)).isEqualTo(5);
        assertThat(available("tenant-a", 8)).isEqualTo(4);
        assertThat(results).hasSize(1);
        assertThat(json.readTree(results.getFirst().payload()).required("unavailableProductIds"))
                .hasToString("[8]");
        assertThat(jdbc.queryForObject(
                        "SELECT result_type FROM inventory_reservation_result", String.class))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                        "SELECT unavailable_product_ids FROM inventory_reservation_result", String.class))
                .isEqualTo("8");
        assertThat(jdbc.queryForObject(
                        "SELECT convert_from(payload, 'UTF8') FROM moduvera_message_outbox", String.class))
                .contains("unavailableProductIds").contains("8").doesNotContain("tenant-a");
    }

    @Test
    void invalidContractIsRejectedBeforeInventoryApplicationBehavior() throws Exception {
        ReserveInventoryCommand command =
                command("invalid-contract", 45, List.of(new ReserveInventoryLine(7, 2)));
        SerializedMessage valid = serialized(command, "tenant-a", "corr-invalid-contract");
        List<SerializedMessage> invalid = List.of(
                withContract(
                        valid,
                        MessageKind.EVENT,
                        valid.descriptor().type(),
                        valid.descriptor().source(),
                        valid.descriptor().destination()),
                withContract(
                        valid,
                        valid.descriptor().kind(),
                        new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v2"),
                        valid.descriptor().source(),
                        valid.descriptor().destination()),
                withContract(
                        valid,
                        valid.descriptor().kind(),
                        valid.descriptor().type(),
                        URI.create("urn:moduvera:reference:untrusted-service"),
                        valid.descriptor().destination()),
                withContract(
                        valid,
                        valid.descriptor().kind(),
                        valid.descriptor().type(),
                        valid.descriptor().source(),
                        new Destination("inventory.adjust")));

        for (SerializedMessage message : invalid) {
            assertThatThrownBy(() -> consumer.accept(messages.toSpringMessage(message)))
                    .isInstanceOf(NonRetryableMessageException.class)
                    .hasMessage("message does not match the expected inbound contract");
        }

        assertThat(available("tenant-a", 7)).isEqualTo(5);
        assertThat(count("inventory_reservation_result")).isZero();
        assertThat(count("moduvera_message_inbox")).isZero();
        assertThat(count("moduvera_message_outbox")).isZero();
        assertThat(observedContext.get()).isNull();
    }

    @Test
    void concurrentReservationsNeverOversellAndProduceOneRejection() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> {
                reserveTogether(ready, start, "concurrent-1", 501);
                return null;
            });
            var second = workers.submit(() -> {
                reserveTogether(ready, start, "concurrent-2", 502);
                return null;
            });
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(available("tenant-a", 7)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_reservation_result WHERE result_type = 'RESERVED'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_reservation_result WHERE result_type = 'REJECTED'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(available("tenant-b", 7)).isEqualTo(100);
    }

    @Test
    void eventFailureRollsBackInboxStockResultAndOutboxAcrossRetries() throws Exception {
        ReserveInventoryCommand command =
                command("reserve-order-rollback", 44, List.of(new ReserveInventoryLine(7, 2)));

        assertThatThrownBy(() -> consumer.accept(
                        messages.toSpringMessage(serialized(command, "tenant-a", "corr-rollback"))))
                .isInstanceOf(NonRetryableMessageException.class);

        assertThat(available("tenant-a", 7)).isEqualTo(5);
        assertThat(count("inventory_reservation_result")).isZero();
        assertThat(count("moduvera_message_inbox")).isZero();
        assertThat(count("moduvera_message_outbox")).isZero();

        // A redelivery after a process/context restart sees no false Inbox completion and can finish.
        consumer.accept(messages.toSpringMessage(serialized(command, "tenant-a", "corr-rollback")));
        assertThat(available("tenant-a", 7)).isEqualTo(3);
        assertThat(count("inventory_reservation_result")).isEqualTo(1);
        assertThat(count("moduvera_message_inbox")).isEqualTo(1);
        assertThat(count("moduvera_message_outbox")).isEqualTo(1);
    }

    @Test
    void staleVersionCannotDeductStock() {
        int updated = ExecutionContextHolder.call(context("tenant-a"), () -> inventoryMapper.reserve(
                "tenant-a", 7, 1, 99, Instant.now(), "test"));
        assertThat(updated).isZero();
        assertThat(available("tenant-a", 7)).isEqualTo(5);
        assertThat(count("flyway_history_inventory")).isPositive();
    }

    private SerializedMessage serialized(
            ReserveInventoryCommand command, String tenantId, String correlationId)
            throws Exception {
        var descriptor = new MessageDescriptor(
                new MessageId(command.commandId()),
                MessageKind.ASYNC_COMMAND,
                new MessageType(ReserveInventoryCommand.MESSAGE_TYPE),
                URI.create("urn:moduvera:reference:order-service"),
                new Destination(ReserveInventoryCommand.DESTINATION),
                Instant.parse("2026-08-30T00:00:00Z"),
                new TenantId(tenantId),
                new Actor(
                        ActorType.SERVICE,
                        "order-service",
                        Set.of("inventory:admin", "catalog:write")),
                correlationId,
                null,
                new Initiator(ActorType.USER, "alice"),
                Long.toString(command.orderId()));
        return SerializedMessage.json(descriptor, json.writeValueAsString(command));
    }

    private static SerializedMessage withContract(
            SerializedMessage original,
            MessageKind kind,
            MessageType type,
            URI source,
            Destination destination) {
        MessageDescriptor descriptor = original.descriptor();
        return new SerializedMessage(
                new MessageDescriptor(
                        descriptor.id(),
                        kind,
                        type,
                        source,
                        destination,
                        descriptor.time(),
                        descriptor.tenantId(),
                        descriptor.actor(),
                        descriptor.correlationId(),
                        descriptor.causationId(),
                        descriptor.initiator(),
                        descriptor.partitionKey()),
                original.contentType(),
                original.payload());
    }

    private void reserveTogether(
            CountDownLatch ready, CountDownLatch start, String commandId, long orderId)
            throws Exception {
        ready.countDown();
        start.await();
        consumer.accept(messages.toSpringMessage(serialized(
                command(commandId, orderId, List.of(new ReserveInventoryLine(7, 4))),
                "tenant-a",
                "corr-" + commandId)));
    }

    private static ReserveInventoryCommand command(
            String commandId, long orderId, List<ReserveInventoryLine> lines) {
        return new ReserveInventoryCommand(commandId, orderId, lines);
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

    private int available(String tenantId, long productId) {
        return jdbc.queryForObject(
                "SELECT available FROM inventory_stock WHERE tenant_id = ? AND product_id = ?",
                Integer.class,
                tenantId,
                productId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long consumedReserveRecords() {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(properties)) {
            return consumedReserveRecords(admin);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "could not observe inventory consumer progress for " + RESERVE_TOPIC, failure);
        }
    }

    private static long consumedReserveRecords(Admin admin) throws Exception {
        return admin.listConsumerGroupOffsets("inventory-it")
                .partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS)
                .entrySet()
                .stream()
                .filter(entry -> RESERVE_TOPIC.equals(entry.getKey().topic()))
                .mapToLong(entry -> entry.getValue().offset())
                .sum();
    }

    private List<SerializedMessage> kafkaResults(String messageId) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "inventory-result-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<SerializedMessage> observed = new ArrayList<>();
        try (var resultConsumer = new KafkaConsumer<String, byte[]>(properties)) {
            resultConsumer.subscribe(List.of(RESULT_TOPIC));
            Instant deadline = Instant.now().plusSeconds(10);
            Instant quietDeadline = null;
            while (Instant.now().isBefore(deadline)
                    && (quietDeadline == null || Instant.now().isBefore(quietDeadline))) {
                for (var record : resultConsumer.poll(Duration.ofMillis(200))) {
                    SerializedMessage result = messages.fromSpringMessage(MessageBuilder
                            .withPayload(record.value())
                            .setHeader(
                                    MessageHeaders.CONTENT_TYPE,
                                    KafkaMessageMapper.STRUCTURED_CLOUD_EVENT)
                            .build());
                    if (messageId.equals(result.descriptor().id().value())) {
                        observed.add(result);
                        quietDeadline = Instant.now().plusSeconds(1);
                    }
                }
            }
        }
        return observed;
    }

    private static void eventually(java.util.function.BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition did not become true before timeout");
            }
            Thread.sleep(50);
        }
    }

    private static ExecutionContext context(String tenantId) {
        return new ExecutionContext(
                new TenantId(tenantId),
                new Actor(ActorType.SERVICE, "order-service", Set.of("inventory:reserve")),
                new Initiator(ActorType.USER, "alice"),
                "corr-test");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestOverrides {

        @Bean
        AtomicReference<ExecutionContext> observedInventoryExecutionContext() {
            return new AtomicReference<>();
        }

        @Bean
        @Primary
        InventoryResultPublisher failingResultPublisher(
                DurablePublication outbox,
                ObjectMapper json,
                Clock clock,
                AtomicReference<ExecutionContext> observedContext) {
            var delegate = new OutboxInventoryResultPublisher(outbox, json, clock);
            var failures = new AtomicInteger();
            return result -> {
                observedContext.set(ExecutionContextHolder.require());
                delegate.publish(result);
                if ("reserve-order-rollback".equals(result.commandId())
                        && failures.incrementAndGet() <= 3) {
                    throw new IllegalStateException("injected result publication failure");
                }
            };
        }
    }
}
