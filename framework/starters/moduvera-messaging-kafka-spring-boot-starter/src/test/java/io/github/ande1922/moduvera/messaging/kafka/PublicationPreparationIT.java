package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxBatch;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxMessage;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxPublishReport;
import io.github.ande1922.moduvera.message.outbox.OutboxStore;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real database admission and fault windows; transport is an explicit committed-state boundary probe. */
@Testcontainers
class PublicationPreparationIT {
    private static final boolean AGENT = Boolean.getBoolean("moduvera.test.agent");
    private static final ContextKey<String> CONTEXT_KEY = ContextKey.named("preparation-fixture");
    private static final TraceContextCarrier CREATION = new TraceContextCarrier(
            "00-" + "6".repeat(32) + "-" + "7".repeat(16) + "-01", "vendor=creation");
    private static final String EXISTING = "00-" + "8".repeat(32) + "-" + "9".repeat(16) + "-01";
    private static final List<Map<String, Object>> RECEIPTS = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> LOGS = Collections.synchronizedList(new ArrayList<>());
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("postgresql.test.image", "postgres:18.6"));
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            System.getProperty("mysql.test.image", "mysql:8.4.11"));

    @Test
    void postgresqlCommitsPreparationBeforeSendAndFencesFailures() throws Exception {
        verify(new Database(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), JdbcMessagingDialect.POSTGRESQL));
    }

    @Test
    void mysqlCommitsPreparationBeforeSendAndFencesFailures() throws Exception {
        verify(new Database(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), JdbcMessagingDialect.MYSQL));
    }

    private void verify(Database db) throws Exception {
        int logsBefore = LOGS.size();
        try (var ignored = new RecoveryLogs()) {
            direct(db, "missing", null, null, null);
            direct(db, "corrupt", CREATION, "invalid-parent", " ");
            direct(db, "invalid-creation", new TraceContextCarrier("invalid-creation", null), null, null);
            direct(db, "existing", CREATION, EXISTING, "vendor=publication");
            direct(db, "bad-state", CREATION, EXISTING, "invalid state");
            direct(db, "unsampled", CREATION, EXISTING.substring(0, EXISTING.length() - 2) + "00", "vendor=unsampled");
            workerReadsCommittedValues(db);
            rejectedAndRolledBackPreparation(db);
            concurrentSameClaim(db);
            concurrentClaimReplacement(db);
            if (AGENT) {
                databaseWriteFailure(db);
                leaseExpiresBeforeCas(db);
            }
        }
        assertThat(LOGS.size() - logsBefore).isEqualTo(AGENT ? 5 : 0);
        receipt(db, "summary", Map.of("governed", AGENT, "recoveryLogs", LOGS.size() - logsBefore,
                "actualDatabase", true, "transportIsCommitProbe", true));
    }

    private void direct(Database db, String operation, TraceContextCarrier creation, String parent, String state) throws Exception {
        db.seed(operation, creation, parent, state);
        var batch = db.store.claim(1, Duration.ofMinutes(1)).orElseThrow();
        var entry = batch.messages().getFirst();
        var before = db.row();
        var result = inManagement(db, operation, () -> db.store.preparePublication(entry, batch.claimToken())).orElseThrow();
        var after = db.row();
        assertThat(withoutPublication(after)).isEqualTo(withoutPublication(before));
        assertThat(result.message().descriptor()).isEqualTo(entry.message().descriptor());
        assertThat(result.message().payload()).containsExactly(entry.message().payload());
        assertThat(result.failedAttempts()).isEqualTo(2);
        assertThat(result.publicationGeneration()).isEqualTo(((Number) before.get("publication_generation")).longValue());
        assertThat(result.publicationTraceParent()).isEqualTo(after.get("publication_traceparent"));
        assertThat(result.publicationTraceState()).isEqualTo(after.get("publication_tracestate"));
        boolean repair = List.of("missing", "corrupt", "invalid-creation").contains(operation);
        if (repair && AGENT) {
            assertThat(result.publicationTraceParent()).isNotBlank().isNotEqualTo(parent);
        } else {
            assertThat(result.publicationTraceParent()).isEqualTo(parent);
            assertThat(result.publicationTraceState()).isEqualTo(state);
        }
        // A fresh adapter and fresh DataSource recover the stored value, without process-local state.
        var fresh = db.store(new NamedParameterJdbcTemplate(db.externalSource), db.externalTransactions);
        var repeated = inManagement(db, operation + "-repeat", () -> fresh.preparePublication(entry, batch.claimToken())).orElseThrow();
        assertEquivalent(repeated, result);
        assertThat(db.row()).isEqualTo(after);
        assertThat(db.wakes.get()).isEqualTo(1);
        receipt(db, operation, traceRow(after));
    }

    private void workerReadsCommittedValues(Database db) throws Exception {
        db.seed("worker", CREATION, null, null);
        var sends = new AtomicInteger();
        var prepared = new AtomicReference<String>();
        var transactions = db.controlled(() -> {}, status -> {
            // Independent connection still sees the pre-repair state until the actual transaction commits.
            assertThat(db.row().get("publication_traceparent")).isNull();
            assertThat(sends.get()).isZero();
            prepared.set(db.jdbc.queryForObject("SELECT publication_traceparent FROM moduvera_message_outbox", String.class));
        });
        var controlled = db.store(new NamedParameterJdbcTemplate(db.source), transactions);
        var worker = worker(new PreparationRoute(db.store, controlled), message -> {
            assertThat(db.row().get("publication_traceparent")).isEqualTo(prepared.get());
            if (AGENT) {
                assertThat(prepared.get()).isNotBlank();
            }
            assertThat(ExecutionContextHolder.require().correlationId()).isEqualTo("management-worker");
            sends.incrementAndGet();
            receipt(db, "send", traceRow(db.row()));
        });
        var report = inManagement(db, "worker", () -> worker.publishBatch(1));
        assertThat(report).isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 0));
        assertThat(sends.get()).isEqualTo(1);
        assertThat(db.row().get("status")).isEqualTo("PUBLISHED");
        assertThat(db.wakes.get()).isEqualTo(1);
    }

    private void rejectedAndRolledBackPreparation(Database db) throws Exception {
        db.seed("rollback", CREATION, "invalid-parent", null);
        var rollback = db.store(new NamedParameterJdbcTemplate(db.source), db.controlled(() -> {}, status -> {
            assertThat(db.row().get("publication_traceparent")).isEqualTo("invalid-parent");
            status.setRollbackOnly();
        }));
        var sends = new AtomicInteger();
        var report = inManagement(db, "rollback", () -> worker(new PreparationRoute(db.store, rollback), ignored -> sends.incrementAndGet())
                .publishBatch(1));
        assertThat(report).isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));
        assertThat(sends.get()).isZero();
        assertThat(db.row().get("publication_traceparent")).isEqualTo("invalid-parent");
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(3);
        assertThat(db.row().get("creation_traceparent")).isEqualTo(CREATION.traceParent());
        receipt(db, "rollback", traceRow(db.row()));

        var claimed = db.store.claim(1, Duration.ofMinutes(1)).orElseThrow();
        var entry = claimed.messages().getFirst();
        var before = db.row();
        assertThat(inManagement(db, "stale", () -> db.store.preparePublication(entry, "stale-token"))).isEmpty();
        inManagement(db, "caller-transaction", () -> db.transactions.execute(status -> {
            assertThatThrownBy(() -> db.store.preparePublication(entry, claimed.claimToken()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("own committed transaction");
            return null;
        }));
        var noTransaction = db.store(new NamedParameterJdbcTemplate(db.source), TransactionOperations.withoutTransaction());
        assertThatThrownBy(() -> inManagement(db, "no-transaction", () -> noTransaction.preparePublication(entry, claimed.claimToken())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("new writable transaction");
        assertThat(db.row()).isEqualTo(before);
        receipt(db, "rejections", Map.of("stale", true, "callerTransaction", true, "noTransaction", true, "unchanged", true));
    }

    private void concurrentSameClaim(Database db) throws Exception {
        db.seed("concurrent", CREATION, null, null);
        var claimed = db.store.claim(1, Duration.ofMinutes(1)).orElseThrow();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                ready.countDown();
                await(start);
                return inManagement(db, "concurrent-one", () -> db.store.preparePublication(claimed.messages().getFirst(), claimed.claimToken()));
            });
            var second = pool.submit(() -> {
                ready.countDown();
                await(start);
                return inManagement(db, "concurrent-two", () -> db.store.preparePublication(claimed.messages().getFirst(), claimed.claimToken()));
            });
            await(ready);
            start.countDown();
            var result = first.get(30, TimeUnit.SECONDS).orElseThrow();
            assertEquivalent(second.get(30, TimeUnit.SECONDS).orElseThrow(), result);
            assertThat(result.publicationTraceParent()).isEqualTo(db.row().get("publication_traceparent"));
        }
        receipt(db, "concurrent", traceRow(db.row()));
    }

    private void concurrentClaimReplacement(Database db) throws Exception {
        db.seed("replacement", CREATION, null, null);
        var claimed = db.store.claim(1, Duration.ofMinutes(1)).orElseThrow();
        var started = new CountDownLatch(1);
        var replaced = new CountDownLatch(1);
        var delayed = db.store(new NamedParameterJdbcTemplate(db.source), db.controlled(() -> {
            started.countDown();
            await(replaced);
        }, status -> {}));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> inManagement(db, "replacement", () -> delayed.preparePublication(
                    claimed.messages().getFirst(), claimed.claimToken())));
            await(started);
            db.external.update("UPDATE moduvera_message_outbox SET claim_token = ?, publication_traceparent = ?", "new-owner", EXISTING);
            var accepted = db.row();
            replaced.countDown();
            assertThat(result.get(30, TimeUnit.SECONDS)).isEmpty();
            assertThat(db.row()).isEqualTo(accepted);
        }
        receipt(db, "replacement", traceRow(db.row()));
    }

    private void databaseWriteFailure(Database db) throws Exception {
        db.seed("write-failure", CREATION, "invalid-parent", null);
        db.jdbc.execute("ALTER TABLE moduvera_message_outbox ADD CONSTRAINT fixture_publication_guard "
                + "CHECK (publication_traceparent = 'invalid-parent')");
        try {
            var sends = new AtomicInteger();
            var report = inManagement(db, "write-failure", () -> worker(db.store, ignored -> sends.incrementAndGet()).publishBatch(1));
            assertThat(report).isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));
            assertThat(sends.get()).isZero();
            assertThat(db.row().get("publication_traceparent")).isEqualTo("invalid-parent");
            assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(3);
            receipt(db, "write-failure", traceRow(db.row()));
        } finally {
            db.jdbc.execute("ALTER TABLE moduvera_message_outbox DROP "
                    + (db.dialect == JdbcMessagingDialect.MYSQL ? "CHECK" : "CONSTRAINT") + " fixture_publication_guard");
        }
    }

    private void leaseExpiresBeforeCas(Database db) throws Exception {
        db.seed("expired", CREATION, null, null);
        var jdbc = new NamedParameterJdbcTemplate(db.source) {
            @Override
            public int update(String sql, Map<String, ?> parameters) {
                if (sql.contains("SET publication_traceparent")) {
                    // Deterministic lease-loss injection after the production locked read and SDK preparation.
                    db.jdbc.update("UPDATE moduvera_message_outbox SET claim_expires_at = ?", java.sql.Timestamp.from(Instant.EPOCH));
                }
                return super.update(sql, parameters);
            }
        };
        var expires = db.store(jdbc, db.transactions);
        var sends = new AtomicInteger();
        var report = inManagement(db, "expired", () -> worker(new PreparationRoute(db.store, expires), ignored -> sends.incrementAndGet())
                .publishBatch(1));
        assertThat(report).isEqualTo(new OutboxPublishReport(1, 0, 0, 1, 0));
        assertThat(sends.get()).isZero();
        assertThat(db.row().get("publication_traceparent")).isNull();
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(2);
        receipt(db, "expired", traceRow(db.row()));
    }

    private static OutboxWorker worker(OutboxStore store, MessageTransport transport) {
        return new OutboxWorker(store, transport, Clock.systemUTC(), Duration.ofMinutes(1), Duration.ZERO, 5);
    }

    private static void assertEquivalent(ClaimedOutboxMessage actual, ClaimedOutboxMessage expected) {
        assertThat(actual.message().descriptor()).isEqualTo(expected.message().descriptor());
        assertThat(actual.message().contentType()).isEqualTo(expected.message().contentType());
        assertThat(actual.message().payload()).containsExactly(expected.message().payload());
        assertThat(actual.failedAttempts()).isEqualTo(expected.failedAttempts());
        assertThat(actual.publicationGeneration()).isEqualTo(expected.publicationGeneration());
        assertThat(actual.publicationTraceParent()).isEqualTo(expected.publicationTraceParent());
        assertThat(actual.publicationTraceState()).isEqualTo(expected.publicationTraceState());
    }

    private static <T> T inManagement(Database db, String operation, Supplier<T> work) {
        var span = GlobalOpenTelemetry.getTracer("moduvera.preparation.fixture").spanBuilder("fixture.preparation")
                .setNoParent().setAttribute("fixture.operation", db.name + "-" + operation).startSpan();
        var identity = new ExecutionContext(new TenantId("management-tenant"), new Actor(ActorType.SERVICE, "management-service"),
                new Initiator(ActorType.USER, "management-user"), "management-" + operation);
        Map<String, String> originalMdc = MDC.getCopyOfContextMap();
        MDC.put("fixture_probe", "pre-existing");
        try (var business = ExecutionContextHolder.open(identity); var scope = Context.current().with(span).with(CONTEXT_KEY, operation).makeCurrent()) {
            var current = Span.current().getSpanContext();
            var mdc = MDC.getCopyOfContextMap();
            try {
                return work.get();
            } finally {
                assertThat(Span.current().getSpanContext()).isEqualTo(current);
                assertThat(Context.current().get(CONTEXT_KEY)).isEqualTo(operation);
                assertThat(ExecutionContextHolder.require()).isSameAs(identity);
                assertThat(MDC.getCopyOfContextMap()).isEqualTo(mdc);
            }
        } finally {
            span.end();
            if (originalMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(originalMdc);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static Map<String, Object> withoutPublication(Map<String, Object> row) {
        var copy = new HashMap<>(row);
        copy.remove("publication_traceparent");
        copy.remove("publication_tracestate");
        return copy;
    }

    private static Map<String, Object> traceRow(Map<String, Object> row) {
        var result = new HashMap<String, Object>();
        for (String key : List.of("message_id", "correlation_id", "creation_traceparent", "creation_tracestate",
                "publication_traceparent", "publication_tracestate", "publication_generation", "attempt_count", "status")) {
            result.put(key, row.get(key));
        }
        return result;
    }

    private static void receipt(Database db, String phase, Map<String, Object> fields) {
        var result = new HashMap<>(fields);
        result.put("database", db.name);
        result.put("phase", phase);
        var now = Instant.now();
        result.put("recordedAtNanos", now.getEpochSecond() * 1_000_000_000L + now.getNano());
        RECEIPTS.add(result);
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        String evidence = System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR");
        if (evidence != null) {
            Files.writeString(Path.of(evidence, "preparation-receipts.json"), new ObjectMapper().writeValueAsString(RECEIPTS));
            Files.writeString(Path.of(evidence, "preparation-logs.jsonl"), String.join("", LOGS));
        }
    }

    private static final class RecoveryLogs extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger("outbox.recovery");
        private final ModuveraEcsStructuredLogFormatter formatter = new ModuveraEcsStructuredLogFormatter(new StandardEnvironment());

        private RecoveryLogs() {
            start();
            logger.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent event) {
            LOGS.add(formatter.format(event));
        }

        @Override
        public void close() {
            logger.detachAppender(this);
            stop();
        }
    }

    private static final class Database {
        private final String name;
        private final JdbcMessagingDialect dialect;
        private final DataSource source;
        private final DataSource externalSource;
        private final JdbcTemplate jdbc;
        private final JdbcTemplate external;
        private final TransactionTemplate transactions;
        private final TransactionTemplate externalTransactions;
        private final AtomicInteger wakes = new AtomicInteger();
        private final JdbcOutboxStore store;

        private Database(String url, String username, String password, JdbcMessagingDialect dialect) {
            this.dialect = dialect;
            name = dialect.name().toLowerCase(java.util.Locale.ROOT);
            source = new DriverManagerDataSource(url, username, password);
            externalSource = new DriverManagerDataSource(url, username, password);
            jdbc = new JdbcTemplate(source);
            external = new JdbcTemplate(externalSource);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            externalTransactions = new TransactionTemplate(new DataSourceTransactionManager(externalSource));
            new DatabaseMigrator(source).migrate(new MigrationPlan(new DatabaseComponent("messaging"),
                    List.of("classpath:db/moduvera-messaging/" + name), true));
            store = store(new NamedParameterJdbcTemplate(source), transactions);
        }

        private JdbcOutboxStore store(NamedParameterJdbcTemplate operations, TransactionOperations transactionOperations) {
            var wake = new LocalOutboxWakeSignal();
            wake.listen(wakes::incrementAndGet);
            return new JdbcOutboxStore(operations, dialect, transactionOperations, wake);
        }

        private void seed(String operation, TraceContextCarrier creation, String parent, String state) {
            jdbc.update("DELETE FROM moduvera_message_outbox");
            wakes.set(0);
            String id = name + "-" + operation;
            var message = SerializedMessage.json(new MessageDescriptor(new MessageId(id), MessageKind.EVENT,
                    new MessageType("io.github.ande1922.moduvera.preparation.created.v1"), URI.create("urn:service:origin"),
                    new Destination("preparation.events"), Instant.parse("2026-09-01T00:00:00Z"), new TenantId("origin-tenant"),
                    new Actor(ActorType.SERVICE, "origin-service"), "original-" + id, new MessageId("original-cause"),
                    new Initiator(ActorType.USER, "origin-user"), "origin-key", creation),
                    "{\"body\":\"preparation-private-body-sentinel\"}");
            transactions.executeWithoutResult(status -> store.appendIntent(message));
            jdbc.update("UPDATE moduvera_message_outbox SET publication_traceparent = ?, publication_tracestate = ?, "
                    + "publication_generation = ?, attempt_count = 2", parent, state, operation.equals("missing") ? 0 : 7);
        }

        private Map<String, Object> row() {
            var row = external.queryForMap("SELECT * FROM moduvera_message_outbox");
            try {
                row.put("payload", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((byte[]) row.get("payload"))));
            } catch (java.security.NoSuchAlgorithmException failure) {
                throw new IllegalStateException(failure);
            }
            return row;
        }

        private TransactionOperations controlled(Runnable before, Consumer<TransactionStatus> after) {
            return new TransactionOperations() {
                @Override
                public <T> T execute(TransactionCallback<T> callback) {
                    return transactions.execute(status -> {
                        before.run();
                        T result = callback.doInTransaction(status);
                        after.accept(status);
                        return result;
                    });
                }
            };
        }
    }

    /** Routes only the preparation fault seam; every claim, CAS and disposition still uses real JDBC. */
    private record PreparationRoute(JdbcOutboxStore normal, JdbcOutboxStore preparation) implements OutboxStore {
        @Override
        public Optional<ClaimedOutboxBatch> claim(int limit, Duration lease) { return normal.claim(limit, lease); }
        @Override
        public Optional<ClaimedOutboxMessage> preparePublication(ClaimedOutboxMessage entry, String token) {
            return preparation.preparePublication(entry, token);
        }
        @Override
        public boolean markPublished(MessageId id, String token, Instant at) { return normal.markPublished(id, token, at); }
        @Override
        public boolean markFailed(MessageId id, String token, Duration delay, String failure) { return normal.markFailed(id, token, delay, failure); }
        @Override
        public boolean markTerminal(MessageId id, String token, Instant at, String failure) { return normal.markTerminal(id, token, at, failure); }
    }
}
