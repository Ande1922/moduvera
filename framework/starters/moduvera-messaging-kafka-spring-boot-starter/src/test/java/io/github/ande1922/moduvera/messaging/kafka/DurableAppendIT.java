package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.github.ande1922.moduvera.message.publication.DurablePublicationTransactionException;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseIdentityException;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Production migrations and transaction adapters on both databases; Agent runs additionally reconcile exported spans. */
@Testcontainers
class DurableAppendIT {
    private static final List<Map<String, Object>> RECEIPTS = new ArrayList<>();
    private static final ContextKey<String> CONTEXT_KEY = ContextKey.named("durable-fixture");
    private static final TraceContextCarrier SUPPLIED = new TraceContextCarrier(
            "00-" + "3".repeat(32) + "-" + "4".repeat(16) + "-01", "vendor=original");
    private static final List<String> TRACE_COLUMNS = List.of("creation_traceparent", "creation_tracestate",
            "publication_traceparent", "publication_tracestate", "publication_generation");
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("postgresql.test.image", "postgres:18.6"));
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            System.getProperty("mysql.test.image", "mysql:8.4.11"));
    @TempDir
    private Path history;

    @Test
    void postgresqlPreservesHistoryAndAtomicallyAppendsTraceMetadata() throws Exception {
        verify(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), JdbcMessagingDialect.POSTGRESQL);
    }

    @Test
    void mysqlPreservesHistoryAndAtomicallyAppendsTraceMetadata() throws Exception {
        verify(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), JdbcMessagingDialect.MYSQL);
    }

    private void verify(String url, String username, String password, JdbcMessagingDialect dialect) throws Exception {
        try (var logs = new FrameworkLogs()) {
            verifyDatabase(url, username, password, dialect);
            assertThat(logs.list).as("append cannot log committed business or Broker success").isEmpty();
        }
    }

    private void verifyDatabase(String url, String username, String password, JdbcMessagingDialect dialect) throws Exception {
        String database = dialect.name().toLowerCase(java.util.Locale.ROOT);
        DataSource dataSource = new DriverManagerDataSource(url, username, password);
        DataSource other = new DriverManagerDataSource(url, username, password);
        var jdbc = new JdbcTemplate(dataSource);
        var external = new JdbcTemplate(other);
        var migrator = new DatabaseMigrator(dataSource);
        String location = "db/moduvera-messaging/" + database;
        var current = new MigrationPlan(new DatabaseComponent("messaging"), List.of("classpath:" + location), false);
        assertThatThrownBy(() -> migrator.validate(current)).isInstanceOf(DatabaseIdentityException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, "moduvera_database_components")).isZero();

        Path previous = Files.createDirectory(history.resolve(database));
        for (String migration : List.of("V1__create_moduvera_messaging.sql", "V2__narrow_tenant_id.sql")) {
            try (var source = getClass().getClassLoader().getResourceAsStream(location + "/" + migration)) {
                assertThat(source).isNotNull();
                Files.copy(source, previous.resolve(migration));
            }
        }
        migrator.migrate(new MigrationPlan(new DatabaseComponent("messaging"), List.of("filesystem:" + previous), true));
        insertHistory(jdbc);
        var before = rows(jdbc, true);
        assertThatThrownBy(() -> migrator.validate(current)).hasMessageContaining("pending migrations remain");
        assertThat(rows(jdbc, true)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, "moduvera_message_outbox", "publication_generation")).isZero();
        migrator.migrate(current);
        assertThat(migrator.validate(current).validationSuccessful).isTrue();
        assertThat(rows(jdbc, true)).isEqualTo(before);
        for (var row : jdbc.queryForList("SELECT * FROM moduvera_message_outbox")) {
            assertThat(((Number) row.get("publication_generation")).longValue()).isZero();
            for (String column : TRACE_COLUMNS.subList(0, 4)) {
                assertThat(row.get(column)).isNull();
            }
        }
        receipt(database, "migration", Map.of("legacyRows", 3, "legacyFieldsPreserved", true, "generation", 0,
                "normalValidationExecutedDdl", false));
        jdbc.execute("CREATE TABLE durable_business (id VARCHAR(128) PRIMARY KEY)");
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var wake = new LocalOutboxWakeSignal();
        var wakes = new AtomicInteger();
        wake.listen(wakes::incrementAndGet);
        var store = new JdbcOutboxStore(new NamedParameterJdbcTemplate(dataSource), dialect, transactions, wake);
        var publication = new JdbcDurablePublication(dataSource, store);

        invoke(database, "commit", () -> transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO durable_business(id) VALUES (?)", "commit");
            publication.append(message(database, "commit", null));
            assertThat(wakes).hasValue(0);
            assertThat(count(external, "moduvera_message_outbox", database + "-commit")).isZero();
            assertThat(count(external, "durable_business", "commit")).isZero();
            receipt(database, "inside-commit", stored(jdbc, database + "-commit"));
        }));
        assertThat(wakes).hasValue(1);
        assertThat(count(external, "durable_business", "commit")).isEqualTo(1);
        var committed = stored(external, database + "-commit");
        receipt(database, "committed", committed);

        invoke(database, "rollback", () -> transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO durable_business(id) VALUES (?)", "rollback");
            publication.append(message(database, "rollback", null));
            receipt(database, "inside-rollback", stored(jdbc, database + "-rollback"));
            assertThat(count(external, "moduvera_message_outbox", database + "-rollback")).isZero();
            assertThat(wakes).hasValue(1);
            status.setRollbackOnly();
        }));
        assertThat(count(external, "moduvera_message_outbox", database + "-rollback")).isZero();
        assertThat(count(external, "durable_business", "rollback")).isZero();
        assertThat(wakes).hasValue(1);
        assertThatThrownBy(() -> invoke(database, "duplicate", () -> transactions.executeWithoutResult(status ->
                publication.append(message(database, "commit", null))))).isInstanceOf(DuplicateKeyException.class);
        assertThat(wakes).hasValue(1);
        assertThat(stored(jdbc, database + "-commit")).isEqualTo(committed);

        for (String id : List.of("supplied", "unsampled", "root")) {
            var original = message(database, id, id.equals("supplied") ? SUPPLIED : null);
            invoke(database, id, () -> transactions.executeWithoutResult(status -> publication.append(original)));
            assertThat(original.descriptor().creationContext()).isEqualTo(id.equals("supplied") ? SUPPLIED : null);
            receipt(database, id, stored(external, database + "-" + id));
            if (id.equals("root") && !Boolean.getBoolean("moduvera.test.agent")) {
                assertThat(stored(external, database + "-root").get("creation_traceparent")).isNull();
            }
        }
        assertThat(wakes).hasValue(4);
        assertThatThrownBy(() -> invoke(database, "no-transaction", () -> publication.append(message(database, "rejected", null))))
                .isInstanceOf(DurablePublicationTransactionException.class);
        var readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> invoke(database, "read-only", () -> readOnly.executeWithoutResult(status ->
                publication.append(message(database, "rejected", null))))).isInstanceOf(DurablePublicationTransactionException.class);
        var wrong = new TransactionTemplate(new DataSourceTransactionManager(other));
        assertThatThrownBy(() -> invoke(database, "wrong-source", () -> wrong.executeWithoutResult(status ->
                publication.append(message(database, "rejected", null))))).isInstanceOf(DurablePublicationTransactionException.class);
        assertThat(wakes).hasValue(4);
        assertThat(count(jdbc, "moduvera_message_outbox", database + "-rejected")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moduvera_message_outbox", Integer.class)).isEqualTo(7);

        // Raw corrupt publication must be claimable for Ticket11 repair, without mutating immutable creation.
        jdbc.update("UPDATE moduvera_message_outbox SET publication_generation = 7, publication_traceparent = ?, "
                + "publication_tracestate = '' WHERE message_id = ?", "invalid-parent", database + "-supplied");
        var claimed = store.claim(20, Duration.ofSeconds(30)).orElseThrow();
        assertThat(claimed.messages()).hasSize(5);
        for (var entry : claimed.messages()) {
            var descriptor = entry.message().descriptor();
            if (descriptor.id().value().equals("legacy-PENDING")) {
                assertThat(descriptor.creationContext()).isNull();
                assertThat(entry.failedAttempts()).isEqualTo(3);
                assertThat(entry.publicationGeneration()).isZero();
                assertThat(entry.publicationTraceParent()).isNull();
            } else {
                String id = descriptor.id().value().substring(database.length() + 1);
                var expected = message(database, id, descriptor.creationContext());
                assertThat(descriptor).isEqualTo(expected.descriptor());
                assertThat(entry.message().payload()).containsExactly(expected.payload());
                assertThat(entry.message().contentType()).isEqualTo(expected.contentType());
                assertThat(entry.failedAttempts()).isZero();
                if (id.equals("supplied")) {
                    assertThat(descriptor.creationContext()).isEqualTo(SUPPLIED);
                    assertThat(entry.publicationGeneration()).isEqualTo(7);
                    assertThat(entry.publicationTraceParent()).isEqualTo("invalid-parent");
                    assertThat(entry.publicationTraceState()).isEmpty();
                } else {
                    assertThat(entry.publicationGeneration()).isZero();
                    assertThat(entry.publicationTraceParent()).isEqualTo(descriptor.creationContext() == null
                            ? null : descriptor.creationContext().traceParent());
                }
            }
        }
        receipt(database, "summary", Map.of("committedMessages", 4, "legacyRows", 3, "wakes", wakes.get(),
                "rollbackRows", 0, "rejectedCalls", 3, "claimedMessages", 5, "independentPublicationMetadata", true,
                "frameworkResultLogs", 0));
    }

    private static void invoke(String database, String id, Runnable work) {
        Map<String, String> initialMdc = MDC.getCopyOfContextMap();
        MDC.put("durable_fixture_probe", id);
        Span root = Span.getInvalid();
        Context selected = Context.root();
        if (id.equals("unsampled")) {
            selected = Context.root().with(Span.wrap(SpanContext.create("5".repeat(32), "6".repeat(16),
                    TraceFlags.getDefault(), TraceState.builder().put("vendor", "unsampled").build())));
        } else if (!id.equals("root")) {
            root = GlobalOpenTelemetry.getTracer("moduvera.durable.fixture").spanBuilder("fixture.durable")
                    .setNoParent().setAttribute("fixture.operation", database + "-" + id).startSpan();
            selected = Context.root().with(root);
        }
        var business = new ExecutionContext(new TenantId("tenant-a"), new Actor(ActorType.SERVICE, "durable-publisher"),
                new Initiator(ActorType.USER, "user-" + id), "caller-" + database + "-" + id);
        try (var identity = ExecutionContextHolder.open(business); var trace = selected.with(CONTEXT_KEY, id).makeCurrent()) {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            var parent = Span.current().getSpanContext();
            receipt(database, "caller", Map.of("operation", id, "trace", parent.getTraceId(), "span", parent.getSpanId(),
                    "valid", parent.isValid(), "sampled", parent.isSampled()));
            try {
                work.run();
            } finally {
                assertThat(Span.current().getSpanContext()).isEqualTo(parent);
                assertThat(Context.current().get(CONTEXT_KEY)).isEqualTo(id);
                assertThat(ExecutionContextHolder.require()).isSameAs(business);
                assertThat(MDC.getCopyOfContextMap()).isEqualTo(previousMdc);
            }
        } finally {
            root.end();
            if (initialMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(initialMdc);
            }
        }
    }

    private static SerializedMessage message(String database, String id, TraceContextCarrier creation) {
        return SerializedMessage.json(new MessageDescriptor(new MessageId(database + "-" + id), MessageKind.EVENT,
                new MessageType("io.github.ande1922.moduvera.durable.created.v1"), URI.create("urn:service:durable"),
                new Destination("durable.events"), Instant.parse("2026-09-01T00:00:00Z"), new TenantId("tenant-a"),
                new Actor(ActorType.SERVICE, "durable-publisher"), "original-" + database + "-" + id,
                new MessageId("cause-" + id), new Initiator(ActorType.USER, "user-" + id), "key-" + id, creation),
                "{\"body\":\"durable-private-body-sentinel\"}");
    }

    private static int count(JdbcTemplate jdbc, String table, String id) {
        String column = table.equals("durable_business") ? "id" : "message_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }

    private static Map<String, Object> stored(JdbcTemplate jdbc, String id) {
        return jdbc.queryForMap("SELECT message_id, correlation_id, creation_traceparent, creation_tracestate, "
                + "publication_traceparent, publication_tracestate, publication_generation FROM moduvera_message_outbox WHERE message_id = ?", id);
    }

    private static List<Map<String, Object>> rows(JdbcTemplate jdbc, boolean legacyOnly) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        for (var source : jdbc.queryForList("SELECT * FROM moduvera_message_outbox ORDER BY message_id")) {
            var row = new LinkedHashMap<String, Object>();
            for (var entry : source.entrySet()) {
                if (!legacyOnly || !TRACE_COLUMNS.contains(entry.getKey())) {
                    Object value = entry.getValue();
                    row.put(entry.getKey(), value instanceof byte[] bytes
                            ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) : value);
                }
            }
            result.add(row);
        }
        return result;
    }

    private static void insertHistory(JdbcTemplate jdbc) {
        for (String status : List.of("PENDING", "LEASED", "TERMINAL")) {
            jdbc.update("""
                    INSERT INTO moduvera_message_outbox(message_id, message_kind, message_type, source, destination,
                        occurred_at, tenant_id, actor_type, actor_subject, correlation_id, causation_id,
                        initiator_type, initiator_subject, partition_key, content_type, payload, status,
                        next_attempt_at, attempt_count, claim_token, claim_expires_at, terminal_at, last_failure)
                    VALUES (?, 'EVENT', 'legacy.created.v1', 'urn:service:legacy', 'legacy.events',
                        ?, 'tenant-legacy', 'SERVICE', 'legacy', ?, ?, 'USER', 'legacy-user', ?, 'application/json', ?, ?,
                        ?, 3, ?, ?, ?, 'OriginalFailure')
                    """, "legacy-" + status, Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),
                    "legacy-corr-" + status, "legacy-cause-" + status, "legacy-key-" + status,
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), status.equals("LEASED") ? "PENDING" : status,
                    Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")), "legacy-token-" + status,
                    Timestamp.from(status.equals("LEASED") ? Instant.now().plus(Duration.ofHours(1)) : Instant.parse("2025-01-01T00:00:00Z")),
                    status.equals("TERMINAL") ? Timestamp.from(Instant.parse("2025-02-01T00:00:00Z")) : null);
        }
    }

    private static void receipt(String database, String phase, Map<String, Object> values) {
        var row = new HashMap<>(values);
        row.put("database", database);
        row.put("phase", phase);
        Instant recordedAt = Instant.now();
        row.put("recordedAtNanos", recordedAt.getEpochSecond() * 1_000_000_000L + recordedAt.getNano());
        RECEIPTS.add(row);
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        String evidence = System.getenv("MODUVERA_OBSERVABILITY_EVIDENCE_DIR");
        if (evidence != null) {
            Files.writeString(Path.of(evidence, "durable-receipts.json"), new ObjectMapper().writeValueAsString(RECEIPTS));
        }
    }

    private static final class FrameworkLogs extends ListAppender<ILoggingEvent> implements AutoCloseable {
        private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        private FrameworkLogs() {
            start();
            root.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent event) {
            String logger = event.getLoggerName();
            if (event.getLevel().isGreaterOrEqual(Level.INFO) && (logger.startsWith("mq.")
                    || logger.startsWith("outbox.") || logger.startsWith("io.github.ande1922.moduvera."))) {
                super.append(event);
            }
        }

        @Override
        public void close() {
            root.detachAppender(this);
            stop();
        }
    }
}
