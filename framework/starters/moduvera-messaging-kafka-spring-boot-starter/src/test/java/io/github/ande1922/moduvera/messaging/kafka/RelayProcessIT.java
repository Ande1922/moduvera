package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.ande1922.moduvera.messaging.kafka.RelayFixtureSupport.*;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.data.spring.SpringTransactionBoundary;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import io.github.ande1922.moduvera.testing.ProgressBarrier;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext
@SpringBootTest(classes = RelayProcessIT.FixtureConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class RelayProcessIT {
    private static final String GROUP = "relay-process-inbox";
    private static final List<Map<String, Object>> RECEIPTS = new CopyOnWriteArrayList<>();
    @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("postgresql.test.image", "postgres:18.6"));
    @Container @SuppressWarnings("deprecation") private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(System.getProperty("kafka.test.image", "confluentinc/cp-kafka:7.3.3")));
    @TempDir Path temporary;
    @Autowired OutboxWorker configuredWorker;
    @Autowired MessageTransport transport;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
        var producer = new HashMap<String, Object>();
        RelayFixtureChild.producerProperties(producer);
        producer.forEach((key, value) -> properties.add(key, () -> value));
        properties.add("spring.cloud.function.definition", () -> "observe");
        properties.add("spring.cloud.stream.bindings.observe-in-0.destination", () -> TOPIC);
        properties.add("spring.cloud.stream.bindings.observe-in-0.group", () -> GROUP);
        properties.add("spring.cloud.stream.bindings.observe-in-0.consumer.max-attempts", () -> 1);
        properties.add("spring.cloud.stream.bindings.observe-in-0.consumer.use-native-decoding", () -> true);
        properties.add("spring.cloud.stream.kafka.bindings.observe-in-0.consumer.configuration.interceptor.classes", () -> RawProbe.class.getName());
        properties.add("moduvera.messaging.kafka.consumer-bindings[0]", () -> "observe-in-0");
    }

    @Test void realKafkaAckWriteFailureTakeoverAndIndependentProcessRestart() throws Exception {
        assertThat(transport).isInstanceOf(StreamBridgeMessageTransport.class);
        var db = new Database(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), JdbcMessagingDialect.POSTGRESQL);
        try (var logs = new Logs()) {
            normalAndAutomaticRetry(db, logs);
            ackWriteFailure(db, logs);
            takeover(db, logs);
            independentRestart(db);
            String id = db.seed("broker-failure");
            KAFKA.stop();
            var worker = worker(db.store, transport, 2);
            assertThat(inManagement(id, () -> worker.publishBatch(1)).failed()).isEqualTo(1);
            assertThat(inManagement(id, () -> worker.publishBatch(1)).failed()).isEqualTo(1);
            assertThat(db.row().get("status")).isEqualTo("TERMINAL");
            assertThat(logs.events(id, "mq.produce")).hasSize(2);
            assertThat(logs.events(id, "outbox.recovery")).hasSize(1);
            assertThat(logs.events(id, "outbox.publish.failure")).hasSize(1);
            assertThat(String.join("", logs.lines)).doesNotContain(PRIVATE_BODY, PRIVATE_CAUSE);
            var nativeErrors = logs.lines.stream().map(new ObjectMapper()::readTree)
                    .filter(event -> event.path("log").path("logger").asString().equals("org.springframework.kafka.support.LoggingProducerListener"))
                    .filter(event -> event.path("log").path("level").asString().equals("ERROR")).toList();
            assertThat(nativeErrors).isEmpty();
            RECEIPTS.add(Map.of("phase", "broker-stopped", "id", id, "row", db.row(), "nativeFinalErrors", nativeErrors.size()));
            writeEvidence("relay-process", RECEIPTS, logs);
        }
    }

    private void normalAndAutomaticRetry(Database db, Logs logs) {
        String id = db.seed("real-first");
        var progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        assertThat(inManagement(id, () -> configuredWorker.publishBatch(1)).published()).isEqualTo(1);
        progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "first send");
        assertBusiness(id, 1, 1);
        assertThat(logs.events(id, "mq.produce")).hasSize(1);
        RECEIPTS.add(Map.of("phase", "published", "id", id, "row", db.row()));

        id = db.seed("real-retry");
        String retryId = id;
        var attempts = new AtomicInteger();
        var retryWorker = worker(db.store, message -> {
            if (attempts.getAndIncrement() == 0) { throw new IllegalStateException(PRIVATE_CAUSE); }
            transport.send(message);
        }, 3);
        assertThat(inManagement(id, () -> retryWorker.publishBatch(1)).failed()).isEqualTo(1);
        progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        assertThat(inManagement(retryId, () -> retryWorker.publishBatch(1)).published()).isEqualTo(1);
        progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "retry send");
        assertBusiness(id, 1, 1);
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(1);
        RECEIPTS.add(Map.of("phase", "retry-published", "id", id, "row", db.row(), "firstFault", "before-transport"));
    }

    private void ackWriteFailure(Database db, Logs logs) {
        String id = db.seed("real-write-failure");
        var before = db.row();
        var progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        db.jdbc.execute("ALTER TABLE moduvera_message_outbox ADD CONSTRAINT relay_process_write_fault CHECK (status <> 'PUBLISHED')");
        try {
            assertThatThrownBy(() -> inManagement(id, () -> configuredWorker.publishBatch(1))).isInstanceOf(DataAccessException.class);
        } finally {
            db.jdbc.execute("ALTER TABLE moduvera_message_outbox DROP CONSTRAINT relay_process_write_fault");
        }
        progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "ACK before failed write");
        assertThat(db.row()).isEqualTo(before);
        assertThat(logs.events(id, "task.execute").getFirst().path("transport_result").asString()).isEqualTo("success");
        assertThat(logs.events(id, "task.execute").getFirst().path("outbox_write_result").asString()).isEqualTo("failed");
        RECEIPTS.add(Map.of("phase", "ack-write-failed", "id", id, "row", db.row()));
        db.expire();
        progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        assertThat(inManagement(id, () -> configuredWorker.publishBatch(1)).published()).isEqualTo(1);
        progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "resend after failed write");
        assertBusiness(id, 2, 1);
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isZero();
        RECEIPTS.add(Map.of("phase", "ack-write-recovered", "id", id, "row", db.row()));
    }

    private void takeover(Database db, Logs logs) {
        String id = db.seed("real-takeover");
        var progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        var oldWorker = worker(db.store, message -> {
            transport.send(message);
            db.expire();
            assertThat(db.store.claim(1, Duration.ofMinutes(1))).isPresent();
        }, 2);
        assertThat(inManagement(id, () -> oldWorker.publishBatch(1)).staleUpdates()).isEqualTo(1);
        progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "stale ACK");
        assertThat(db.row().get("status")).isEqualTo("PENDING");
        assertThat(logs.events(id, "task.execute").getFirst().path("outbox_write_result").asString()).isEqualTo("stale");
        db.expire();
        progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        assertThat(inManagement(id, () -> configuredWorker.publishBatch(1)).published()).isEqualTo(1);
        progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "takeover resend");
        assertBusiness(id, 2, 1);
        RECEIPTS.add(Map.of("phase", "takeover-published", "id", id, "row", db.row()));
    }

    private void independentRestart(Database db) throws Exception {
        String id = db.seed("process-crash");
        var before = db.row();
        String evidence = System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR");
        Path directory = evidence == null ? temporary : Path.of(evidence, "relay-process-children");
        Files.createDirectories(directory);
        var progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        Process first = child(directory, "crash");
        try {
            await(() -> Files.exists(directory.resolve("ack-before-mark.json")), first, directory.resolve("crash-output.log"));
            progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "crashed child ACK");
            assertBusiness(id, 1, 1);
            assertThat(db.row()).isEqualTo(before);
            awaitProducerExport(id, first, directory.resolve("crash-output.log"));
            var checkpoint = new ObjectMapper().readTree(Files.readString(directory.resolve("ack-before-mark.json")));
            assertThat(checkpoint.path("pid").asLong()).isEqualTo(first.pid());
            RECEIPTS.add(Map.of("phase", "before-kill", "id", id, "row", db.row(), "checkpoint", checkpoint));
            first.destroyForcibly();
            assertThat(first.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(first.exitValue()).isNotZero();
            assertThat(db.row()).isEqualTo(before);
            db.expire();
            progress = ProgressBarrier.capture(RelayProcessIT::consumed);
            Process second = child(directory, "restart");
            try {
                assertThat(second.pid()).isNotEqualTo(first.pid());
                await(() -> Files.exists(directory.resolve("restart-complete.json")), second, directory.resolve("restart-output.log"));
                assertThat(second.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(second.exitValue()).isZero();
                progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "fresh child duplicate");
                assertBusiness(id, 2, 1);
                var after = db.row();
                assertThat(after.get("status")).isEqualTo("PUBLISHED");
                var retained = new HashMap<>(after); retained.put("status", "PENDING");
                assertThat(retained).isEqualTo(before);
                var completion = new ObjectMapper().readTree(Files.readString(directory.resolve("restart-complete.json")));
                assertThat(completion.path("canonicalCount").asLong()).isEqualTo(1);
                var firstLogs = Files.readString(directory.resolve("crash-logs.jsonl"));
                assertThat(firstLogs).contains("mq.produce").doesNotContain("task.execute");
                RECEIPTS.add(Map.of("phase", "restart", "id", id, "row", after, "firstPid", first.pid(),
                        "firstExit", first.exitValue(), "secondPid", second.pid(), "secondExit", second.exitValue(), "completion", completion));
            } finally {
                if (second.isAlive()) { second.destroyForcibly(); second.waitFor(30, TimeUnit.SECONDS); }
                second.close();
            }
        } finally {
            if (first.isAlive()) { first.destroyForcibly(); first.waitFor(30, TimeUnit.SECONDS); }
            first.close();
        }
    }

    private Process child(Path directory, String mode) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (AGENT) {
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (argument.startsWith("-Dotel.") || (argument.startsWith("-javaagent:") && !argument.contains("jacoco"))) {
                    command.add(argument);
                }
            }
            command.add("-Dmoduvera.test.agent=true");
        }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(RelayFixtureChild.class.getName());
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(mode + "-output.log").toFile());
        var environment = builder.environment();
        environment.put("RELAY_FIXTURE_JDBC_URL", POSTGRES.getJdbcUrl());
        environment.put("RELAY_FIXTURE_JDBC_USER", POSTGRES.getUsername());
        environment.put("RELAY_FIXTURE_JDBC_PASSWORD", POSTGRES.getPassword());
        environment.put("RELAY_FIXTURE_BROKERS", KAFKA.getBootstrapServers());
        environment.put("RELAY_FIXTURE_DIRECTORY", directory.toString());
        environment.put("RELAY_FIXTURE_MODE", mode);
        return builder.start();
    }

    private static void awaitProducerExport(String id, Process child, Path output) throws Exception {
        if (!AGENT) { return; }
        var wire = RECEIPTS.stream().filter(row -> row.get("phase").equals("broker-wire") && row.get("id").equals(id)).findFirst().orElseThrow();
        String producer = ((String) wire.get("traceparent")).split("-")[2];
        Path spans = Path.of(System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR"), "spans.jsonl");
        await(() -> {
            try {
                for (String line : Files.readAllLines(spans)) {
                    try {
                        var span = new ObjectMapper().readTree(line);
                        if (span.path("spanId").asString().equals(producer) && span.path("kind").asString().equals("PRODUCER")) { return true; }
                    } catch (RuntimeException incompleteLine) { /* The receiver may still be appending its final line. */ }
                }
                return false;
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }, child, output);
    }

    private static void await(BooleanSupplier condition, Process child, Path output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!condition.getAsBoolean()) {
            if (!child.isAlive() || System.nanoTime() >= deadline) {
                throw new AssertionError("child checkpoint unavailable; output=" + Files.readString(output));
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private void assertBusiness(String id, int records, int applied) {
        assertThat(RECEIPTS.stream().filter(row -> "broker-wire".equals(row.get("phase")) && id.equals(row.get("id")))).hasSize(records);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_relay_business WHERE message_id = ?", Integer.class, id)).isEqualTo(applied);
        var outcomes = RECEIPTS.stream().filter(row -> "inbox".equals(row.get("phase")) && id.equals(row.get("id"))).toList();
        assertThat(outcomes).hasSize(records);
        assertThat(outcomes.stream().filter(row -> "APPLIED".equals(row.get("outcome")))).hasSize(applied);
        assertThat(outcomes.stream().filter(row -> "DUPLICATE".equals(row.get("outcome")))).hasSize(records - applied);
    }

    private static long consumed() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            return admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).entrySet().stream()
                    .filter(entry -> TOPIC.equals(entry.getKey().topic())).mapToLong(entry -> entry.getValue().offset()).sum();
        } catch (Exception failure) { throw new IllegalStateException("cannot observe Relay fixture consumer progress", failure); }
    }

    public static final class RawProbe implements ConsumerInterceptor<byte[], byte[]> {
        @Override public ConsumerRecords<byte[], byte[]> onConsume(ConsumerRecords<byte[], byte[]> records) {
            records.forEach(record -> {
                var envelope = new ObjectMapper().readTree(record.value());
                var parent = record.headers().lastHeader("traceparent");
                int parents = 0; for (var ignored : record.headers().headers("traceparent")) { parents++; }
                RECEIPTS.add(Map.of("phase", "broker-wire", "id", envelope.path("id").asString(), "offset", record.offset(),
                        "traceparent", parent == null ? "absent" : new String(parent.value(), StandardCharsets.UTF_8),
                        "headerCount", parents, "creation", envelope.has("traceparent") ? envelope.path("traceparent").asString() : "absent"));
            });
            return records;
        }
        @Override public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {}
        @Override public void close() {}
        @Override public void configure(Map<String, ?> config) {}
    }

    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    static class FixtureConfiguration {
        @Bean ObjectMapper fixtureObjectMapper() { return new ObjectMapper(); }
        @Bean InboxTemplate fixtureInbox(DataSource source, InboxRepository repository, PlatformTransactionManager transactions) {
            new DatabaseMigrator(source).migrate(new MigrationPlan(new DatabaseComponent("messaging"), List.of("classpath:db/moduvera-messaging/postgresql"), true));
            new JdbcTemplate(source).execute("CREATE TABLE fixture_relay_business (message_id VARCHAR(128) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL)");
            return new InboxTemplate(GROUP, repository, new SpringTransactionBoundary(new TransactionTemplate(transactions)), Clock.systemUTC());
        }
        @Bean Consumer<Message<byte[]>> observe(ReliableMessageConsumerFactory factory, InboxTemplate inbox, JdbcTemplate jdbc) {
            var contract = new InboundMessageContract(MessageKind.EVENT,
                    new MessageType("io.github.ande1922.moduvera.relay.created.v1"), URI.create("urn:service:origin"),
                    new Destination(TOPIC), new Actor(ActorType.SERVICE, "local-consumer", Set.of("local:consume")));
            var endpoint = factory.forContract(contract, message -> {
                String id = message.descriptor().id().value();
                var identity = ExecutionContextHolder.require();
                assertThat(identity.correlationId()).isEqualTo("original-" + id);
                assertThat(identity.actor().subjectId()).isEqualTo("local-consumer");
                var outcome = inbox.handle(message.descriptor().id(), () -> jdbc.update(
                        "INSERT INTO fixture_relay_business (message_id, tenant_id) VALUES (?, ?)", id, identity.requireTenantId().value()));
                RECEIPTS.add(Map.of("phase", "inbox", "id", id, "outcome", outcome.name(), "trace", trace()));
            });
            return endpoint::accept;
        }
    }
}
