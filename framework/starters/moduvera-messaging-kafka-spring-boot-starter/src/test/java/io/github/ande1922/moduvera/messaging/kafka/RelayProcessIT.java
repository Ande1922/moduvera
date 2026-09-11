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
            redriveRecovery(db);
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

    private void redriveRecovery(Database db) throws Exception {
        try (var consumerLogs = new Logs(null, List.of("mq.consume"))) {
            redriveRecovery(db, consumerLogs);
        }
    }

    private void redriveRecovery(Database db, Logs consumerLogs) throws Exception {
        db.jdbc.update("DELETE FROM moduvera_message_outbox");
        String id = "postgresql-redrive-chain";
        var durable = new JdbcDurablePublication(db.source, db.store);
        inManagement(id, () -> db.transactions.execute(status -> { durable.append(message(id, null)); return null; }));
        var immutable = immutableMessage(db);
        var initial = db.row();
        assertThat(((Number) initial.get("publication_generation")).intValue()).isZero();
        var progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        db.jdbc.execute("ALTER TABLE moduvera_message_outbox ADD CONSTRAINT redrive_ack_write_fault CHECK (status <> 'PUBLISHED')");
        try {
            assertThatThrownBy(() -> inManagement(id, () -> configuredWorker.publishBatch(1))).isInstanceOf(DataAccessException.class);
        } finally {
            db.jdbc.execute("ALTER TABLE moduvera_message_outbox DROP CONSTRAINT redrive_ack_write_fault");
        }
        progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "original generation ACK");
        assertBusiness(id, 1, 1);
        db.expire();
        assertThat(worker(db.store, ignored -> { throw new IllegalStateException(PRIVATE_CAUSE); }, 1).publishBatch(1).failed()).isEqualTo(1);
        assertThat(db.row().get("status")).isEqualTo("TERMINAL");
        RECEIPTS.add(Map.of("phase", "redrive-original-terminal", "id", id, "row", db.row()));

        String evidence = System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR");
        Path directory = evidence == null ? temporary.resolve("redrive") : Path.of(evidence, "redrive-process-children");
        Files.createDirectories(directory);
        Path management = directory.resolve("management");
        Files.createDirectories(management);
        Process redriver = child(management, "redrive", RedriveFixtureChild.class);
        Map<String, Object> secondGeneration;
        try {
            await(() -> Files.exists(management.resolve("redrive-committed.json")), redriver, management.resolve("redrive-output.log"));
            secondGeneration = db.row();
            assertThat(secondGeneration.get("status")).isEqualTo("PENDING");
            assertThat(((Number) secondGeneration.get("publication_generation")).intValue()).isEqualTo(1);
            assertThat(immutableMessage(db)).isEqualTo(immutable);
            var checkpoint = new ObjectMapper().readTree(Files.readString(management.resolve("redrive-committed.json")));
            assertThat(checkpoint.path("pid").asLong()).isEqualTo(redriver.pid());
            redriver.destroyForcibly();
            assertThat(redriver.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(redriver.exitValue()).isNotZero();
            assertThat(db.row()).isEqualTo(secondGeneration);
            RECEIPTS.add(Map.of("phase", "redrive-root-lost", "id", id, "row", secondGeneration,
                    "pid", redriver.pid(), "exit", redriver.exitValue(), "checkpoint", checkpoint));
        } finally {
            if (redriver.isAlive()) { redriver.destroyForcibly(); redriver.waitFor(30, TimeUnit.SECONDS); }
            redriver.close();
        }
        retryGeneration(db, id, secondGeneration, 2);
        crashGeneration(db, id, directory.resolve("generation-1-crash"), 2);
        db.expire();
        Path terminalDirectory = directory.resolve("generation-1-terminal");
        var terminalCompletion = completeChild(terminalDirectory, "terminal");
        assertThat(db.row().get("status")).isEqualTo("TERMINAL");
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(3);
        assertGeneration(db.row(), secondGeneration);
        assertThat(immutableMessage(db)).isEqualTo(immutable);
        RECEIPTS.add(Map.of("phase", "redrive-second-terminal", "id", id, "row", db.row(), "completion", terminalCompletion));

        var wakes = new AtomicInteger();
        var administration = new JdbcOutboxStore(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(db.source),
                JdbcMessagingDialect.POSTGRESQL, db.transactions, wakes::incrementAndGet);
        String token = db.external.queryForObject("SELECT claim_token FROM moduvera_message_outbox", String.class);
        assertThat(inManagement(id, () -> administration.redrive(new io.github.ande1922.moduvera.message.MessageId(id), token))).isTrue();
        assertThat(wakes).hasValue(1);
        var thirdGeneration = db.row();
        assertThat(((Number) thirdGeneration.get("publication_generation")).intValue()).isEqualTo(2);
        if (AGENT) {
            assertThat(thirdGeneration.get("publication_traceparent")).isNotEqualTo(secondGeneration.get("publication_traceparent"))
                    .isNotEqualTo(initial.get("publication_traceparent"));
        }
        assertThat(immutableMessage(db)).isEqualTo(immutable);
        RECEIPTS.add(Map.of("phase", "redrive-third-accepted", "id", id, "row", thirdGeneration, "wakes", wakes.get()));
        retryGeneration(db, id, thirdGeneration, 4);
        crashGeneration(db, id, directory.resolve("generation-2-crash"), 3);
        db.expire();
        progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        var restartCompletion = completeChild(directory.resolve("generation-2-restart"), "restart");
        progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "third generation restart duplicate");
        assertBusiness(id, 4, 1);
        assertThat(db.row().get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(4);
        assertGeneration(db.row(), thirdGeneration);
        assertThat(immutableMessage(db)).isEqualTo(immutable);
        RECEIPTS.add(Map.of("phase", "redrive-final-published", "id", id, "row", db.row(),
                "completion", restartCompletion, "records", 4, "applied", 1, "duplicates", 3, "immutableColumnsAndPayloadVerified", true));
        assertThat(consumerLogs.events(id, "mq.consume")).hasSize(4);
        writeEvidence("redrive-inbound", RECEIPTS.stream().filter(row -> id.equals(row.get("id"))).toList(), consumerLogs);
    }

    private void retryGeneration(Database db, String id, Map<String, Object> generation, int count) {
        assertThat(inManagement(id, () -> worker(db.store, ignored -> { throw new IllegalStateException(PRIVATE_CAUSE); }, 10)
                .publishBatch(1)).failed()).isEqualTo(1);
        assertThat(db.row().get("status")).isEqualTo("PENDING");
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(count);
        if (AGENT) { assertGeneration(db.row(), generation); }
        // Without an SDK no root is invented; governed preparation remains the only recovery path.
        RECEIPTS.add(Map.of("phase", "redrive-automatic-retry", "id", id, "row", db.row(), "pid", ProcessHandle.current().pid()));
    }

    private void crashGeneration(Database db, String id, Path directory, int records) throws Exception {
        Files.createDirectories(directory);
        var before = db.row();
        var progress = ProgressBarrier.capture(RelayProcessIT::consumed);
        Process process = child(directory, "crash");
        try {
            await(() -> Files.exists(directory.resolve("ack-before-mark.json")), process, directory.resolve("crash-output.log"));
            progress.awaitAdvanceBy(1, Duration.ofSeconds(45), Duration.ofMillis(50), () -> "new generation ACK before crash");
            assertBusiness(id, records, 1);
            assertThat(db.row()).isEqualTo(before);
            // Wait for this record's producer, not the first record for this reused MessageId.
            awaitProducerExport(id, process, directory.resolve("crash-output.log"));
            var checkpoint = new ObjectMapper().readTree(Files.readString(directory.resolve("ack-before-mark.json")));
            assertThat(checkpoint.path("pid").asLong()).isEqualTo(process.pid());
            process.destroyForcibly();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
            assertThat(db.row()).isEqualTo(before);
            RECEIPTS.add(Map.of("phase", "redrive-publish-lost", "id", id, "row", db.row(), "pid", process.pid(),
                    "exit", process.exitValue(), "checkpoint", checkpoint, "directory", directory.getFileName().toString()));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(30, TimeUnit.SECONDS); }
            process.close();
        }
    }

    private tools.jackson.databind.JsonNode completeChild(Path directory, String mode) throws Exception {
        Files.createDirectories(directory);
        Process process = child(directory, mode);
        try {
            await(() -> Files.exists(directory.resolve(mode + "-complete.json")), process, directory.resolve(mode + "-output.log"));
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            var completion = new ObjectMapper().readTree(Files.readString(directory.resolve(mode + "-complete.json")));
            assertThat(completion.path("pid").asLong()).isEqualTo(process.pid());
            assertThat(completion.path("canonicalCount").asInt()).isEqualTo(1);
            return completion;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(30, TimeUnit.SECONDS); }
            process.close();
        }
    }

    private static void assertGeneration(Map<String, Object> actual, Map<String, Object> expected) {
        for (String field : List.of("creation_traceparent", "creation_tracestate", "publication_traceparent", "publication_tracestate", "publication_generation")) {
            assertThat(actual.get(field)).as(field).isEqualTo(expected.get(field));
        }
    }

    private static Map<String, Object> immutableMessage(Database db) {
        var fields = new HashMap<>(db.external.queryForMap("SELECT message_id, message_kind, message_type, source, destination, occurred_at,"
                + " tenant_id, actor_type, actor_subject, correlation_id, causation_id, initiator_type, initiator_subject,"
                + " partition_key, content_type, payload, creation_traceparent, creation_tracestate FROM moduvera_message_outbox"));
        fields.put("payload", java.util.HexFormat.of().formatHex((byte[]) fields.get("payload")));
        return fields;
    }

    private Process child(Path directory, String mode) throws Exception {
        return child(directory, mode, RelayFixtureChild.class);
    }

    private Process child(Path directory, String mode, Class<?> mainClass) throws Exception {
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
        command.add(mainClass.getName());
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
        var wire = RECEIPTS.stream().filter(row -> row.get("phase").equals("broker-wire") && row.get("id").equals(id)).toList().getLast();
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
                assertThat(identity.requireTenantId().value()).isEqualTo("origin-tenant");
                assertThat(identity.initiator().subjectId()).isEqualTo("origin-user");
                var outcome = inbox.handle(message.descriptor().id(), () -> jdbc.update(
                        "INSERT INTO fixture_relay_business (message_id, tenant_id) VALUES (?, ?)", id, identity.requireTenantId().value()));
                RECEIPTS.add(Map.of("phase", "inbox", "id", id, "outcome", outcome.name(), "trace", trace()));
            });
            return endpoint::accept;
        }
    }
}
