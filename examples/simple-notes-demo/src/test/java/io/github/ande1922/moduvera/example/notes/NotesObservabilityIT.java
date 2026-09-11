package io.github.ande1922.moduvera.example.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.PublicationLifecycle;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.github.ande1922.moduvera.message.publication.ImmediatePublication;
import io.github.ande1922.moduvera.messaging.kafka.JdbcOutboxStore;
import io.github.ande1922.moduvera.messaging.kafka.MicrometerPublicationObserver;
import io.github.ande1922.moduvera.messaging.kafka.OutboxMaintenance;
import io.github.ande1922.moduvera.testing.ProgressBarrier;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** Independent consumer assembly proof; bottom process-loss/CAS matrices live in framework fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class NotesObservabilityIT {
    private static final String GROUP = "notes-observability-" + UUID.randomUUID();
    private static final String TOPIC = GROUP + "-events";
    private static final String METRIC = "moduvera.messaging.outbox.";
    private static final boolean AGENT = Boolean.getBoolean("moduvera.test.agent");
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));
    @Container
    @SuppressWarnings("deprecation")
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(
            System.getProperty("kafka.test.image", "confluentinc/cp-kafka:7.3.3")));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("moduvera.database.migration.mode", () -> "startup");
        properties.add("moduvera.database.migration.initialize", () -> true);
        properties.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
        properties.add("spring.cloud.stream.bindings.noteCreated-in-0.destination", () -> TOPIC);
        properties.add("spring.cloud.stream.bindings.notesCreated-out-0.destination", () -> TOPIC);
        properties.add("spring.cloud.stream.bindings.noteCreated-in-0.group", () -> GROUP);
        properties.add("moduvera.messaging.kafka.relay-enabled", () -> false);
        properties.add("moduvera.messaging.kafka.relay-max-attempts", () -> 2);
        properties.add("moduvera.messaging.kafka.failure-backoff", () -> "0s");
        properties.add("moduvera.messaging.kafka.maintenance-interval", () -> "1h");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired JdbcOutboxStore store;
    @Autowired OutboxWorker worker;
    @Autowired MessageTransport transport;
    @Autowired ImmediatePublication immediate;
    @Autowired PublicationObserver observer;
    @Autowired PublicationLifecycle lifecycle;
    @Autowired OutboxMaintenance maintenance;
    @Autowired MeterRegistry meters;
    @Autowired Clock clock;
    private final List<Map<String, Object>> requests = new ArrayList<>();

    @Test
    void qualifiesHttpPublicationRecoveryAndMetricsThroughPublicComponents() throws Exception {
        assertThat(observer).isInstanceOf(MicrometerPublicationObserver.class);
        maintenance.runOnce();
        var created = request("create", "POST", "/api/v1/notes", "tenant-a-writer", "{\"content\":\"notes-private-body-sentinel\"}", 201);
        String noteId = json.readTree(created.body()).get("id").asText();
        String messageId = jdbc.queryForObject("SELECT message_id FROM moduvera_message_outbox", String.class);
        var immutable = immutableRow(messageId);
        assertThat(immutable.get("correlation_id")).isEqualTo(created.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertThat(immutable.get("tenant_id")).isEqualTo("tenant-a");
        assertThat(immutable.get("initiator_subject")).isEqualTo("alice");
        if (AGENT) assertThat(immutable.get("creation_traceparent")).isNotNull();
        request("read", "GET", "/api/v1/notes/" + noteId, "tenant-a-reader", null, 200);
        request("isolated", "GET", "/api/v1/notes/" + noteId, "tenant-b-writer", null, 404);
        request("forbidden", "POST", "/api/v1/notes", "tenant-a-reader", "{\"content\":\"denied\"}", 403);
        request("invalid", "POST", "/api/v1/notes", "tenant-a-writer", "{\"content\":\"\"}", 400);
        request("anonymous", "GET", "/api/v1/notes/" + noteId, null, null, 401);
        request("unsampled", "GET", "/api/v1/notes/" + noteId, "tenant-a-reader", null, 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_note", Integer.class)).isOne();

        // Actual Kafka ACK followed by an actual PostgreSQL writeback rejection.
        var first = ProgressBarrier.capture(this::consumed);
        jdbc.execute("ALTER TABLE moduvera_message_outbox ADD CONSTRAINT notes_reject_published CHECK (status <> 'PUBLISHED')");
        try {
            assertThatThrownBy(() -> worker.publishBatch(1)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE moduvera_message_outbox DROP CONSTRAINT notes_reject_published");
        }
        await(first);
        assertReceipts(messageId, 1);
        jdbc.update("UPDATE moduvera_message_outbox SET claim_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE message_id = ?", messageId);
        var failing = worker(message -> { throw new IllegalStateException("controlled transport unavailable"); });
        assertThat(failing.publishBatch(1).failed()).isOne();
        assertState(messageId, "PENDING", 1);
        assertThat(failing.publishBatch(1).failed()).isOne();
        assertState(messageId, "TERMINAL", 2);
        String recoveryClaim = jdbc.queryForObject("SELECT claim_token FROM moduvera_message_outbox WHERE message_id = ?", String.class, messageId);
        assertThat(store.redrive(new MessageId(messageId), recoveryClaim)).isTrue();
        assertThat(jdbc.queryForObject("SELECT publication_generation FROM moduvera_message_outbox WHERE message_id = ?", Long.class, messageId)).isEqualTo(1);
        var replay = ProgressBarrier.capture(this::consumed);
        assertThat(worker.publishBatch(1).published()).isOne();
        await(replay);
        assertReceipts(messageId, 1); // committed Inbox precheck skip, not a fabricated InboxOutcome
        assertThat(immutableRow(messageId)).containsExactlyInAnyOrderEntriesOf(immutable);
        assertState(messageId, "PUBLISHED", 2);

        String immediateId = "notes-immediate-" + UUID.randomUUID();
        var immediateProgress = ProgressBarrier.capture(this::consumed);
        var context = new ExecutionContext(new TenantId("tenant-a"), new Actor(ActorType.SERVICE, "notes-demo"),
                new Initiator(ActorType.USER, "alice"), "notes-immediate-correlation");
        var root = GlobalOpenTelemetry.getTracer("notes-consumer-fixture").spanBuilder("notes.immediate.entry")
                .setParent(Context.root()).startSpan();
        try (var scope = root.makeCurrent()) {
            ExecutionContextHolder.run(context, () -> immediate.publish(SerializedMessage.json(
                    new MessageDescriptor(new MessageId(immediateId), MessageKind.EVENT,
                            new MessageType("io.github.ande1922.moduvera.example.notes.created.v1"),
                            URI.create("urn:service:notes-demo"), new Destination("notes.events"), clock.instant(),
                            context.tenantId(), context.actor(), context.correlationId(), null, context.initiator(), "tenant-a:" + noteId),
                    "{\"noteId\":\"" + noteId + "\"}")));
        } finally { root.end(); }
        await(immediateProgress);
        assertReceipts(immediateId, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moduvera_message_outbox WHERE message_id = ?", Integer.class, immediateId)).isZero();

        String terminal = createIntent("terminal");
        assertThat(worker(message -> { throw new NonRetryableMessageException("controlled terminal"); }).publishBatch(1).failed()).isOne();
        assertState(terminal, "TERMINAL", 1);
        String stale = createIntent("stale");
        var staleProgress = ProgressBarrier.capture(this::consumed);
        assertThat(worker(message -> {
            transport.send(message);
            jdbc.update("UPDATE moduvera_message_outbox SET claim_token = 'superseded-notes-claim' WHERE message_id = ?", message.descriptor().id().value());
        }).publishBatch(1).staleUpdates()).isOne();
        await(staleProgress);
        assertReceipts(stale, 1);
        // Keep this unrelated pending row ineligible while the next row is locked.
        jdbc.update("UPDATE moduvera_message_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE message_id = ?", stale);
        String locked = createIntent("locked");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT message_id FROM moduvera_message_outbox WHERE message_id = ? FOR UPDATE")) {
                statement.setString(1, locked);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(worker.publishBatch(1).claimed()).isZero();
                }
            } finally { connection.rollback(); }
        }
        // Age/cleanup fixtures only after immutable-message recovery assertions above.
        jdbc.update("UPDATE moduvera_message_outbox SET occurred_at = CURRENT_TIMESTAMP - INTERVAL '10 seconds' WHERE message_id = ?", locked);
        jdbc.update("UPDATE moduvera_message_outbox SET published_at = CURRENT_TIMESTAMP - INTERVAL '8 days' WHERE message_id = ?", messageId);
        assertThat(maintenance.runOnce()).isOne();
        Map<String, Object> metricEvidence = assertMetrics();
        List<Map<String, Object>> wire = wireEvidence(4);
        if (AGENT) {
            String evidence = System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR");
            assertThat(evidence).isNotBlank();
            var receiverStatus = Path.of(evidence, "receiver-status.json");
            assertThat(json.readTree(Files.readString(receiverStatus)).get("spans").asInt()).isPositive();
            long observationStarted = System.nanoTime();
            // Keep the actual application JVM alive for four configured potential metric export periods.
            Thread.sleep(2000);
            var signals = json.readTree(Files.readString(receiverStatus)).get("postRequestsBySignal");
            assertThat(signals.get("metrics").asInt()).isZero();
            assertThat(signals.get("logs").asInt()).isZero();
            assertThat(System.nanoTime() - observationStarted).isGreaterThanOrEqualTo(Duration.ofSeconds(2).toNanos());
            Files.writeString(Path.of(evidence, "notes-receipt.json"), json.writeValueAsString(Map.of(
                    "requests", requests, "durable_message", messageId, "immediate_message", immediateId,
                    "creation", immutable.get("creation_traceparent"), "wire", wire, "metrics", metricEvidence,
                    "consumer_offset", consumed(), "infrastructure", Map.of("postgresql", POSTGRES.getDockerImageName(), "kafka", KAFKA.getDockerImageName()))));
        }
    }

    private String createIntent(String label) throws Exception {
        request(label, "POST", "/api/v1/notes", "tenant-a-writer", "{\"content\":\"metric fixture\"}", 201);
        return jdbc.queryForObject("SELECT message_id FROM moduvera_message_outbox WHERE status = 'PENDING' AND claim_token IS NULL", String.class);
    }

    private HttpResponse<String> request(String label, String method, String path, String bearer, String body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Correlation-Id", "untrusted-notes-correlation")
                .header("X-Tenant-Id", "forged-tenant").header("X-Actor-Id", "forged-actor")
                .timeout(Duration.ofSeconds(15));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var requestContext = label.equals("unsampled")
                ? Context.root().with(Span.wrap(SpanContext.createFromRemoteParent(
                        "12345678901234567890123456789012", "1234567890123456", TraceFlags.getDefault(), TraceState.getDefault())))
                : Context.current();
        try (var scope = requestContext.makeCurrent(); var client = HttpClient.newHttpClient()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(status);
            String correlation = response.headers().firstValue("X-Correlation-Id").orElseThrow();
            assertThat(UUID.fromString(correlation).version()).isEqualTo(4);
            if (AGENT) {
                String trace = response.headers().firstValue("X-Trace-Id").orElseThrow();
                assertThat(trace).matches("[a-f0-9]{32}");
                if (label.equals("unsampled")) assertThat(trace).isEqualTo("12345678901234567890123456789012");
                requests.add(Map.of("label", label, "correlation", correlation, "trace", trace, "status", status));
            }
            return response;
        }
    }

    private OutboxWorker worker(MessageTransport selected) {
        return new OutboxWorker(store, selected, clock, System::nanoTime, Duration.ofSeconds(30),
                Duration.ofSeconds(2), Duration.ZERO, 2, observer, lifecycle);
    }

    private Map<String, Object> immutableRow(String id) {
        return jdbc.queryForMap("""
                SELECT message_id, message_kind, message_type, source, destination, occurred_at, tenant_id,
                       actor_type, actor_subject, correlation_id, causation_id, initiator_type, initiator_subject,
                       partition_key, content_type, encode(payload, 'hex') AS payload_hex, creation_traceparent, creation_tracestate
                  FROM moduvera_message_outbox WHERE message_id = ?
                """, id);
    }

    private void assertState(String id, String status, int failures) {
        assertThat(jdbc.queryForMap("SELECT status, attempt_count FROM moduvera_message_outbox WHERE message_id = ?", id))
                .containsEntry("status", status).containsEntry("attempt_count", failures);
    }

    private void assertReceipts(String id, int expected) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_note_receipt WHERE message_id = ? AND tenant_id = 'tenant-a'", Integer.class, id)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moduvera_message_inbox WHERE message_id = ?", Integer.class, id)).isEqualTo(expected);
    }

    private long consumed() {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            return admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, java.util.concurrent.TimeUnit.SECONDS)
                    .entrySet().stream().filter(entry -> entry.getKey().topic().equals(TOPIC)).mapToLong(entry -> entry.getValue().offset()).sum();
        } catch (Exception failure) { throw new IllegalStateException("cannot observe Notes consumer offsets", failure); }
    }

    private void await(ProgressBarrier barrier) {
        barrier.awaitAdvanceBy(1, Duration.ofSeconds(25), Duration.ofMillis(100), () -> "notes committed consumption");
    }

    private Map<String, Object> assertMetrics() {
        assertThat(meters.get(METRIC + "claimed").counter().count()).isEqualTo(6);
        assertThat(meters.get(METRIC + "claim.conflicts").counter().count()).isEqualTo(1);
        assertThat(meters.get(METRIC + "cleanup.deleted").counter().count()).isEqualTo(1);
        assertThat(meters.get(METRIC + "pending").gauge().value()).isEqualTo(2);
        assertThat(meters.get(METRIC + "terminal").gauge().value()).isEqualTo(1);
        assertThat(meters.get(METRIC + "pending.oldest.seconds").gauge().value()).isGreaterThanOrEqualTo(9);
        for (var result : Map.of("published", 2, "retry", 1, "terminal", 2).entrySet()) {
            assertThat(meters.find(METRIC + "publish").tag("result", result.getKey()).counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum()).isEqualTo(result.getValue().intValue());
            assertThat(meters.find(METRIC + "broker.ack").tag("result", result.getKey()).timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum()).isEqualTo(result.getValue().intValue());
        }
        assertThat(meters.get(METRIC + "stale.token").tag("operation", "published").counter().count()).isEqualTo(1);
        var names = meters.getMeters().stream().map(meter -> meter.getId().getName()).filter(name -> name.startsWith(METRIC)).distinct().sorted().toList();
        assertThat(names).hasSize(9);
        meters.getMeters().stream().filter(meter -> meter.getId().getName().startsWith(METRIC)).forEach(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                    switch (tag.getKey()) {
                        case "destination" -> assertThat(tag.getValue()).isEqualTo("notes.events");
                        case "type" -> assertThat(tag.getValue()).isEqualTo("io.github.ande1922.moduvera.example.notes.created.v1");
                        case "result" -> assertThat(tag.getValue()).isIn("published", "retry", "terminal");
                        case "operation" -> assertThat(tag.getValue()).isEqualTo("published");
                        default -> throw new AssertionError("Unexpected metric tag: " + tag.getKey());
                    }
                }));
        return Map.of("families", names, "claimed", 6, "published", 2, "retry", 1, "terminal_results", 2,
                "stale", 1, "conflicts", 1, "cleanup", 1, "pending", 2, "terminal_backlog", 1);
    }

    private List<Map<String, Object>> wireEvidence(int expected) throws Exception {
        var properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("group.id", GROUP + "-wire");
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        var result = new ArrayList<Map<String, Object>>();
        try (var consumer = new KafkaConsumer<String, byte[]>(properties, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (result.size() < expected && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    var envelope = json.readTree(record.value());
                    var safe = new LinkedHashMap<String, Object>();
                    for (String field : List.of("id", "source", "type", "destination", "tenantid", "actortype", "actorsubject", "initiatortype", "initiatorsubject", "correlationid", "partitionkey")) safe.put(field, envelope.get(field).asText());
                    assertThat(envelope.get("tenantid").asText()).isEqualTo("tenant-a");
                    assertThat(envelope.get("actorsubject").asText()).isEqualTo("notes-demo");
                    assertThat(envelope.get("initiatorsubject").asText()).isEqualTo("alice");
                    assertThat(record.key()).isEqualTo(envelope.get("partitionkey").asText());
                    if (AGENT) {
                        safe.put("creation", envelope.get("traceparent").asText());
                        var header = record.headers().lastHeader("traceparent");
                        assertThat(header).isNotNull();
                        safe.put("transport", new String(header.value(), StandardCharsets.UTF_8));
                    }
                    safe.put("offset", record.offset());
                    result.add(safe);
                }
            }
        }
        assertThat(result).hasSize(expected);
        return result;
    }
}
