package io.github.ande1922.moduvera.example.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ContextExecutors;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionContextSnapshot;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.data.mybatis.ExecutionContextTenantLineHandler;
import io.github.ande1922.moduvera.example.notes.application.NoteApplicationService;
import io.github.ande1922.moduvera.example.notes.domain.Note;
import io.github.ande1922.moduvera.example.notes.domain.NoteNotFoundException;
import io.github.ande1922.moduvera.example.notes.infrastructure.persistence.MybatisPlusNoteRepository;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxStore;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.messaging.kafka.KafkaMessageMapper;
import io.github.ande1922.moduvera.messaging.kafka.OutboxRelay;
import io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory;
import io.github.ande1922.moduvera.testing.Eventually;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
@Import(NotesDemoIT.ConsumerInterceptorConfiguration.class)
class NotesDemoIT {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final String CONSUMER_GROUP = "notes-demo-" + UUID.randomUUID();
    private static final String FAILURE_GROUP = "notes-failure-" + UUID.randomUUID();
    private static final ExecutionContext COMPOSITION_WORKER = new ExecutionContext(
            ExecutionScope.platform(),
            new Actor(ActorType.SYSTEM, "notes-composition-worker"),
            new Initiator(ActorType.SYSTEM, "notes-composition-runtime"),
            "notes-composition-worker");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    @Container
    @SuppressWarnings("deprecation")
    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(System.getProperty(
                    "kafka.test.image", "confluentinc/cp-kafka:7.3.3")));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("moduvera.database.migration.mode", () -> "startup");
        properties.add("moduvera.database.migration.initialize", () -> true);
        properties.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
        properties.add("spring.cloud.stream.bindings.noteCreated-in-0.destination", () -> topic());
        properties.add("spring.cloud.stream.bindings.notesCreated-out-0.destination", () -> topic());
        properties.add(
                "spring.cloud.stream.bindings.noteCreated-in-0.group",
                () -> CONSUMER_GROUP);
        properties.add("spring.cloud.function.definition", () -> "noteCreated;alwaysFail");
        properties.add("spring.cloud.stream.bindings.alwaysFail-in-0.destination", NotesDemoIT::failureTopic);
        properties.add("spring.cloud.stream.bindings.alwaysFail-out-0.destination", NotesDemoIT::failureTopic);
        properties.add("spring.cloud.stream.bindings.alwaysFail-in-0.group", () -> FAILURE_GROUP);
        properties.add("spring.cloud.stream.bindings.alwaysFail-in-0.consumer.max-attempts", () -> "1");
        properties.add(
                "spring.cloud.stream.kafka.bindings.alwaysFail-in-0.consumer.enable-dlq",
                () -> "true");
        properties.add(
                "spring.cloud.stream.kafka.bindings.alwaysFail-in-0.consumer.dlq-name",
                () -> failureTopic() + "-dlq");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionBoundary transactions;

    @Autowired
    private ExecutionContextTenantLineHandler tenantLineHandler;

    @Autowired
    private MybatisPlusInterceptor mybatisPlusInterceptor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MybatisPlusNoteRepository notes;

    @Autowired
    private NoteApplicationService noteApplicationService;

    @Autowired
    private StreamOperations streams;

    @Autowired
    private KafkaMessageMapper messageMapper;

    @Autowired
    private OutboxStore outbox;

    @Autowired
    private DurablePublication durablePublication;

    @Autowired
    private MessageTransport kafkaTransport;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private AtomicInteger retryAttempts;

    @Autowired
    private List<MigrationDefinition> migrationDefinitions;

    @Test
    void consumesTheScaffoldThroughRealHttpAndPostgresql() throws Exception {
        assertThat(migrationDefinitions)
                .filteredOn(definition -> definition.component().value().equals("notes_demo"))
                .singleElement()
                .satisfies(definition -> {
                    assertThat(definition.postgresqlLocations())
                            .containsExactly("classpath:db/migration/notes");
                    assertThat(definition.mysqlLocations()).isEmpty();
                });
        assertThat(jdbc.queryForList(
                        "SELECT component_name FROM moduvera_database_components ORDER BY component_name",
                        String.class))
                .containsExactly("messaging", "notes_demo");
        assertThat(jdbc.queryForList(
                        "SELECT version FROM flyway_history_messaging WHERE success ORDER BY installed_rank",
                        String.class))
                .containsExactly("0", "1", "2");
        assertThat(jdbc.queryForList(
                        "SELECT version FROM flyway_history_notes_demo WHERE success ORDER BY installed_rank",
                        String.class))
                .containsExactly("0", "10");
        assertThat(transactions).isNotNull();
        assertThat(tenantLineHandler).isNotNull();
        assertThat(mybatisPlusInterceptor.getInterceptors())
                .hasSize(2)
                .first()
                .isInstanceOf(TenantLineInnerInterceptor.class);
        assertThat(mybatisPlusInterceptor.getInterceptors())
                .anyMatch(ConsumerInnerInterceptor.class::isInstance);

        HttpResponse<String> created = send("POST", "/api/v1/notes", "tenant-a-writer", "{\"content\":\"first note\"}", "corr-create");
        assertThat(created.statusCode()).isEqualTo(201);
        assertPublicCorrelation(created, "corr-create");
        assertThat(created.body()).doesNotContain("\"data\"").contains("\"content\":\"first note\"");
        String id = objectMapper.readTree(created.body()).get("id").asText();

        Eventually.await(
                Duration.ofSeconds(20),
                Duration.ofMillis(100),
                () -> count("demo_note_receipt") == 1 && publishedOutbox() == 1,
                () -> "outbox=" + outboxDiagnostic() + ", receipt=" + count("demo_note_receipt"));
        assertThat(kafkaKey()).isEqualTo("tenant-a:" + id);
        assertThat(jdbc.queryForObject(
                        "SELECT convert_from(payload, 'UTF8') FROM moduvera_message_outbox",
                        String.class))
                .doesNotContain("tenant-a");

        relay.stop();
        String crashedMessageId = "crash-after-ack-" + UUID.randomUUID();
        appendIntent(ambiguityMessage(crashedMessageId, id));
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM moduvera_message_outbox
                         WHERE message_id = ? AND status = 'PENDING'
                           AND next_attempt_at <= CURRENT_TIMESTAMP
                           AND (claim_expires_at IS NULL OR claim_expires_at <= CURRENT_TIMESTAMP)
                        """,
                        Integer.class,
                        crashedMessageId))
                .isOne();
        var crashedWorker = worker(message -> {
            kafkaTransport.send(message);
            throw new SimulatedProcessCrash();
        });
        assertThatThrownBy(() -> crashedWorker.publishBatch(1))
                .isInstanceOf(SimulatedProcessCrash.class);

        var takeover = worker(kafkaTransport);
        Eventually.await(
                Duration.ofSeconds(5),
                Duration.ofMillis(100),
                () -> takeover.publishBatch(1).published() == 1,
                () -> outboxDiagnostic(crashedMessageId));
        assertThat(attemptCount(crashedMessageId)).isZero();

        String timedOutMessageId = "timeout-after-accept-" + UUID.randomUUID();
        appendIntent(ambiguityMessage(timedOutMessageId, id));
        var timedOutWorker = worker(message -> {
            kafkaTransport.send(message);
            throw new TimeoutException("simulated ACK timeout after broker acceptance");
        });
        assertThat(timedOutWorker.publishBatch(1).failed()).isEqualTo(1);
        assertThat(attemptCount(timedOutMessageId)).isEqualTo(1);
        assertThat(takeover.publishBatch(1).published()).isEqualTo(1);

        Eventually.await(
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                () -> count("demo_note_receipt") == 3,
                () -> "receipt=" + count("demo_note_receipt"));
        assertThat(kafkaMessageIds(5))
                .filteredOn(crashedMessageId::equals)
                .hasSize(2);
        assertThat(kafkaMessageIds(5))
                .filteredOn(timedOutMessageId::equals)
                .hasSize(2);

        String slowBrokerMessageId = "slow-broker-" + UUID.randomUUID();
        appendIntent(ambiguityMessage(slowBrokerMessageId, id));
        pauseKafka();
        long slowSendStarted = System.nanoTime();
        try (var resume = Executors.newSingleThreadScheduledExecutor()) {
            resume.schedule(NotesDemoIT::unpauseKafkaSafely, 500, TimeUnit.MILLISECONDS);
            assertThat(takeover.publishBatch(1).published()).isEqualTo(1);
        } finally {
            unpauseKafkaSafely();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - slowSendStarted))
                .isGreaterThanOrEqualTo(Duration.ofMillis(400));

        String actualTimeoutMessageId = "actual-timeout-" + UUID.randomUUID();
        appendIntent(ambiguityMessage(actualTimeoutMessageId, id));
        pauseKafka();
        try {
            assertThat(takeover.publishBatch(1).failed()).isEqualTo(1);
        } finally {
            unpauseKafkaSafely();
        }
        Eventually.await(
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                () -> takeover.publishBatch(1).published() == 1,
                () -> outboxDiagnostic(actualTimeoutMessageId));

        Eventually.await(
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                () -> count("demo_note_receipt") == 5,
                () -> "receipt=" + count("demo_note_receipt"));
        assertThat(kafkaMessageIds(7))
                .contains(slowBrokerMessageId, actualTimeoutMessageId);

        streams.send("alwaysFail-out-0", messageMapper.toSpringMessage(failureMessage()));
        Eventually.await(
                Duration.ofSeconds(20),
                Duration.ofMillis(100),
                () -> retryAttempts.get() == 3,
                () -> "retry attempts=" + retryAttempts.get());
        assertThat(kafkaKey(failureTopic() + "-dlq")).isEqualTo("tenant-a:failure-1");

        HttpResponse<String> sameTenant = send("GET", "/api/v1/notes/" + id, "tenant-a-reader", null, "corr-read");
        assertThat(sameTenant.statusCode()).isEqualTo(200);
        assertThat(sameTenant.body()).contains("\"id\":\"" + id + "\"");

        HttpResponse<String> otherTenant = send("GET", "/api/v1/notes/" + id, "tenant-b-writer", null, "corr-cross-tenant");
        assertThat(otherTenant.statusCode()).isEqualTo(404);
        assertThat(otherTenant.body()).contains("\"code\":\"notes.not-found\"");

        HttpResponse<String> forbidden = send("POST", "/api/v1/notes", "tenant-a-reader", "{\"content\":\"blocked\"}", "corr-forbidden");
        assertThat(forbidden.statusCode()).isEqualTo(403);
        String forbiddenCorrelation = assertPublicCorrelation(forbidden, "corr-forbidden");
        assertThat(forbidden.body())
                .contains("\"code\":\"security.permission-denied\"")
                .contains("\"correlationId\":\"" + forbiddenCorrelation + "\"");

        HttpResponse<String> invalid = send("POST", "/api/v1/notes", "tenant-a-writer", "{\"content\":\"\"}", "corr-invalid");
        assertThat(invalid.statusCode()).isEqualTo(400);
        String invalidCorrelation = assertPublicCorrelation(invalid, "corr-invalid");
        assertThat(invalid.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(invalid.body())
                .contains("\"code\":\"request.validation-failed\"")
                .contains("\"correlationId\":\"" + invalidCorrelation + "\"");

        HttpResponse<String> anonymous = send("GET", "/api/v1/notes/" + id, null, null, "corr-anonymous");
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsPlatformAndMissingScopesBeforeTenantReadsAndWritesOnPostgresql() {
        long existingId = 9_000_001;
        long rejectedId = 9_000_002;
        jdbc.update(
                "INSERT INTO demo_note(id, tenant_id, content, created_at) VALUES (?, ?, ?, ?)",
                existingId,
                "tenant-a",
                "guarded note",
                Timestamp.from(Instant.parse("2026-09-05T00:00:00Z")));
        int rowsBeforeRejectedWrites = count("demo_note");
        var platform = platformContext("postgres-platform");
        var rejectedNote = new Note(rejectedId, "must not persist", Instant.parse("2026-09-05T00:01:00Z"));
        try {
            assertThatThrownBy(() -> ExecutionContextHolder.call(
                            platform, () -> notes.findById(existingId)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("tenant execution scope is required at this boundary");
            assertThatThrownBy(() -> notes.findById(existingId))
                    .isInstanceOf(MissingExecutionContextException.class);

            assertThatThrownBy(() -> ExecutionContextHolder.run(
                            platform, () -> notes.save(rejectedNote)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("tenant execution scope is required at this boundary");
            assertThatThrownBy(() -> notes.save(rejectedNote))
                    .isInstanceOf(MissingExecutionContextException.class);
            assertThat(count("demo_note")).isEqualTo(rowsBeforeRejectedWrites);
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM demo_note WHERE id = ?", Integer.class, rejectedId))
                    .isZero();

            assertThat(ExecutionContextHolder.call(
                            tenantContext("tenant-a", "postgres-tenant-a"),
                            () -> notes.findById(existingId)))
                    .isPresent();
            assertThat(ExecutionContextHolder.call(
                            tenantContext("tenant-b", "postgres-tenant-b"),
                            () -> notes.findById(existingId)))
                    .isEmpty();
        } finally {
            jdbc.update("DELETE FROM demo_note WHERE id IN (?, ?)", existingId, rejectedId);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void trustedEntriesPropagateDirectTasksIntoTenantBusinessReadsAndRestoreTheWorker()
            throws Exception {
        long tenantANote = 9_100_001;
        long tenantBNote = 9_100_002;
        int rowsBeforeReads = count("demo_note");
        int outboxBeforeReads = count("moduvera_message_outbox");
        int receiptsBeforeReads = count("demo_note_receipt");
        seedNote(tenantANote, "tenant-a", "tenant a composition");
        seedNote(tenantBNote, "tenant-b", "tenant b composition");

        ExecutionContext firstTenantA = tenantReadContext(
                "tenant-a", "async-reader-a", "origin-a", "async-direct-a");
        ExecutionContext secondTenantA = tenantReadContext(
                "tenant-a", "async-reader-b", "origin-b", "async-direct-b");
        ExecutionContext tenantB = tenantReadContext(
                "tenant-b", "async-reader-c", "origin-c", "async-direct-c");
        ExecutionContext platform = platformReadContext("platform-reader", "async-direct-platform");

        ExecutorService rawTasks = contextWorker("notes-direct-composition");
        try (ExecutorService tasks = ContextExecutors.propagating(rawTasks)) {
            awaitBusinessRead(
                    submitBusinessRead(tasks, firstTenantA, tenantANote),
                    tenantANote,
                    firstTenantA,
                    "notes-direct-composition");
            assertWorkerRestored(rawTasks);
            awaitBusinessRead(
                    submitBusinessRead(tasks, secondTenantA, tenantANote),
                    tenantANote,
                    secondTenantA,
                    "notes-direct-composition");
            assertWorkerRestored(rawTasks);
            awaitBusinessRead(
                    submitBusinessRead(tasks, tenantB, tenantBNote),
                    tenantBNote,
                    tenantB,
                    "notes-direct-composition");
            assertWorkerRestored(rawTasks);

            assertFutureFailure(
                    submitBusinessRead(tasks, tenantB, tenantANote), NoteNotFoundException.class);
            assertWorkerRestored(rawTasks);
            assertFutureFailure(
                    submitBusinessRead(tasks, platform, tenantANote), IllegalStateException.class);
            assertWorkerRestored(rawTasks);
            assertFutureFailure(tasks.submit(() -> businessRead(tenantANote)),
                    MissingExecutionContextException.class);
            assertWorkerRestored(rawTasks);
        } finally {
            deleteNotes(tenantANote, tenantBNote);
        }

        assertThat(count("demo_note")).isEqualTo(rowsBeforeReads);
        assertThat(count("moduvera_message_outbox")).isEqualTo(outboxBeforeReads);
        assertThat(count("demo_note_receipt")).isEqualTo(receiptsBeforeReads);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void registeredCallbacksReadBusinessStateOnTheExternalCompletionThreadAndRestoreIt()
            throws Exception {
        long noteId = 9_100_003;
        int rowsBeforeReads = count("demo_note");
        int outboxBeforeReads = count("moduvera_message_outbox");
        int receiptsBeforeReads = count("demo_note_receipt");
        seedNote(noteId, "tenant-a", "callback composition");
        ExecutionContext firstRequest = tenantReadContext(
                "tenant-a", "callback-reader-a", "callback-origin-a", "callback-a");
        ExecutionContext secondRequest = tenantReadContext(
                "tenant-a", "callback-reader-b", "callback-origin-b", "callback-b");
        CompletableFuture<Long> firstSource = new CompletableFuture<>();
        CompletableFuture<Long> secondSource = new CompletableFuture<>();
        CompletableFuture<BusinessRead> firstRead = registerBusinessRead(firstSource, firstRequest);
        CompletableFuture<BusinessRead> secondRead = registerBusinessRead(secondSource, secondRequest);
        assertThat(ExecutionContextHolder.current()).isEmpty();

        try (ExecutorService sdk = contextWorker("notes-callback-completion")) {
            Thread firstCompletion = completeOnWorker(sdk, firstSource, noteId);
            BusinessRead firstObservation =
                    awaitBusinessRead(firstRead, noteId, firstRequest, "notes-callback-completion");
            assertThat(firstObservation.thread()).isSameAs(firstCompletion);
            assertWorkerRestored(sdk);

            Thread secondCompletion = completeOnWorker(sdk, secondSource, noteId);
            BusinessRead secondObservation =
                    awaitBusinessRead(secondRead, noteId, secondRequest, "notes-callback-completion");
            assertThat(secondObservation.thread()).isSameAs(secondCompletion);
            assertWorkerRestored(sdk);
        } finally {
            deleteNotes(noteId);
        }

        assertThat(count("demo_note")).isEqualTo(rowsBeforeReads);
        assertThat(count("moduvera_message_outbox")).isEqualTo(outboxBeforeReads);
        assertThat(count("demo_note_receipt")).isEqualTo(receiptsBeforeReads);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private Future<BusinessRead> submitBusinessRead(
            ExecutorService tasks, ExecutionContext context, long noteId) {
        return ExecutionContextHolder.call(
                context, () -> tasks.submit(() -> businessRead(noteId)));
    }

    private CompletableFuture<BusinessRead> registerBusinessRead(
            CompletableFuture<Long> source, ExecutionContext context) {
        return ExecutionContextHolder.call(
                context,
                () -> source.thenApply(ExecutionContextSnapshot.capture()
                        .bindFunction(this::businessRead)));
    }

    private BusinessRead businessRead(long noteId) {
        Note note = noteApplicationService.get(noteId);
        return new BusinessRead(note, ExecutionContextHolder.require(), Thread.currentThread());
    }

    private static BusinessRead awaitBusinessRead(
            Future<BusinessRead> result,
            long expectedNoteId,
            ExecutionContext expectedContext,
            String expectedThreadName)
            throws Exception {
        BusinessRead observed = result.get(2, TimeUnit.SECONDS);
        assertThat(observed.note().id()).isEqualTo(expectedNoteId);
        assertThat(observed.context()).isSameAs(expectedContext);
        assertThat(observed.thread().getName()).startsWith(expectedThreadName);
        return observed;
    }

    private static void assertFutureFailure(
            Future<?> result, Class<? extends Throwable> expectedCause) {
        Throwable failure = catchThrowable(() -> result.get(2, TimeUnit.SECONDS));
        assertThat(failure).isInstanceOf(ExecutionException.class);
        assertThat(failure.getCause()).isInstanceOf(expectedCause);
    }

    private static void assertWorkerRestored(ExecutorService worker) throws Exception {
        assertThat(worker.submit(ExecutionContextHolder::require).get(2, TimeUnit.SECONDS))
                .isSameAs(COMPOSITION_WORKER);
    }

    private static Thread completeOnWorker(
            ExecutorService worker, CompletableFuture<Long> source, long noteId)
            throws Exception {
        return worker.submit(() -> {
                    assertThat(source.complete(noteId)).isTrue();
                    assertThat(ExecutionContextHolder.require()).isSameAs(COMPOSITION_WORKER);
                    return Thread.currentThread();
                })
                .get(2, TimeUnit.SECONDS);
    }

    private static ExecutorService contextWorker(String threadName) {
        return Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
                .name(threadName)
                .unstarted(() -> ExecutionContextHolder.run(COMPOSITION_WORKER, task)));
    }

    private void seedNote(long noteId, String tenantId, String content) {
        jdbc.update(
                "INSERT INTO demo_note(id, tenant_id, content, created_at) VALUES (?, ?, ?, ?)",
                noteId,
                tenantId,
                content,
                Timestamp.from(Instant.parse("2026-09-06T00:00:00Z")));
    }

    private void deleteNotes(long... noteIds) {
        for (long noteId : noteIds) {
            jdbc.update("DELETE FROM demo_note WHERE id = ?", noteId);
        }
    }

    private static ExecutionContext tenantReadContext(
            String tenantId, String actor, String initiator, String correlationId) {
        return new ExecutionContext(
                ExecutionScope.tenant(new TenantId(tenantId)),
                new Actor(ActorType.USER, actor, Set.of("notes:read")),
                new Initiator(ActorType.USER, initiator),
                correlationId);
    }

    private static ExecutionContext platformReadContext(String actor, String correlationId) {
        return new ExecutionContext(
                ExecutionScope.platform(),
                new Actor(ActorType.USER, actor, Set.of("notes:read")),
                new Initiator(ActorType.USER, actor),
                correlationId);
    }

    private record BusinessRead(Note note, ExecutionContext context, Thread thread) {}

    private static ExecutionContext platformContext(String correlationId) {
        return ExecutionContext.initiatedBy(
                ExecutionScope.platform(),
                new Actor(ActorType.SYSTEM, "notes-platform-probe"),
                correlationId);
    }

    private static ExecutionContext tenantContext(String tenantId, String correlationId) {
        return ExecutionContext.initiatedBy(
                new TenantId(tenantId),
                new Actor(ActorType.USER, "notes-tenant-probe"),
                correlationId);
    }

    private static String topic() {
        return "notes-events-" + Integer.toUnsignedString(NotesDemoIT.class.getName().hashCode());
    }

    private static String failureTopic() {
        return "notes-failure-" + Integer.toUnsignedString(NotesDemoIT.class.getName().hashCode());
    }

    private static SerializedMessage failureMessage() {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId("failure-message-1"),
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.example.notes.failure.v1"),
                        URI.create("urn:test:notes"),
                        new Destination("failure.events"),
                        Instant.now(),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "notes-test"),
                        "corr-failure",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:failure-1"),
                "{}");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int publishedOutbox() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM moduvera_message_outbox WHERE status = 'PUBLISHED'",
                Integer.class);
    }

    private int attemptCount(String messageId) {
        return jdbc.queryForObject(
                "SELECT attempt_count FROM moduvera_message_outbox WHERE message_id = ?",
                Integer.class,
                messageId);
    }

    private String outboxDiagnostic(String messageId) {
        return jdbc.queryForMap(
                        """
                        SELECT status, attempt_count, claim_expires_at, last_failure
                          FROM moduvera_message_outbox
                         WHERE message_id = ?
                        """,
                        messageId)
                .toString();
    }

    private String outboxDiagnostic() {
        return jdbc.queryForMap(
                        "SELECT status, attempt_count, last_failure FROM moduvera_message_outbox")
                .toString();
    }

    private String kafkaKey() {
        return kafkaKey(topic());
    }

    private String kafkaKey(String kafkaTopic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (var consumer = new KafkaConsumer<String, byte[]>(properties)) {
            consumer.subscribe(java.util.List.of(kafkaTopic));
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(200));
                if (!records.isEmpty()) {
                    return records.iterator().next().key();
                }
            }
        }
        throw new AssertionError("Kafka probe did not observe the note event");
    }

    private List<String> kafkaMessageIds(int expectedCount) throws Exception {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "message-id-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<String> messageIds = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, byte[]>(properties)) {
            consumer.subscribe(List.of(topic()));
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && messageIds.size() < expectedCount) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    messageIds.add(objectMapper.readTree(record.value()).required("id").asText());
                }
            }
        }
        return messageIds;
    }

    private OutboxWorker worker(MessageTransport transport) {
        return new OutboxWorker(
                outbox,
                transport,
                Clock.systemUTC(),
                System::nanoTime,
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                Duration.ZERO,
                3,
                PublicationObserver.noop());
    }

    private void appendIntent(SerializedMessage message) {
        transactions.inTransaction(() -> durablePublication.append(message));
    }

    private static void pauseKafka() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void unpauseKafkaSafely() {
        try {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // Best effort in a finally block; a second unpause is harmless for this test.
        }
    }

    private static SerializedMessage ambiguityMessage(String messageId, String noteId) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(messageId),
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.example.notes.created.v1"),
                        URI.create("urn:service:notes-demo"),
                        new Destination("notes.events"),
                        Instant.now(),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "notes-demo"),
                        "corr-ambiguity",
                        null,
                        new Initiator(ActorType.USER, "tenant-a-writer"),
                        "tenant-a:" + noteId),
                "{\"noteId\":\"" + noteId + "\",\"content\":\"ambiguity\"}");
    }

    private static String assertPublicCorrelation(HttpResponse<String> response, String supplied) {
        String correlation = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(UUID.fromString(correlation).version()).isEqualTo(4);
        assertThat(correlation).isNotEqualTo(supplied);
        return correlation;
    }

    private HttpResponse<String> send(
            String method, String path, String token, String body, String correlationId)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Correlation-Id", correlationId);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConsumerInterceptorConfiguration {

        @Bean
        AtomicInteger retryAttempts() {
            return new AtomicInteger();
        }

        @Bean
        java.util.function.Consumer<org.springframework.messaging.Message<byte[]>> alwaysFail(
                ReliableMessageConsumerFactory factory, AtomicInteger retryAttempts) {
            return factory.forContract(
                    new InboundMessageContract(
                            MessageKind.EVENT,
                            new MessageType("io.github.ande1922.moduvera.example.notes.failure.v1"),
                            URI.create("urn:test:notes"),
                            new Destination("failure.events"),
                            new Actor(ActorType.SERVICE, "notes-test")),
                    ignored -> {
                        retryAttempts.incrementAndGet();
                        throw new IllegalStateException("retryable failure");
                    });
        }

        @Bean
        MybatisPlusInterceptor consumerMybatisPlusInterceptor() {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new ConsumerInnerInterceptor());
            return interceptor;
        }
    }

    private static final class ConsumerInnerInterceptor implements InnerInterceptor {}

    private static final class SimulatedProcessCrash extends Error {

        private static final long serialVersionUID = 1L;
    }
}
