package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.ande1922.moduvera.messaging.kafka.RelayFixtureSupport.*;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.message.MessageId;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real row locks, conditional updates and independent connections; SDK assertions run under the governed Agent. */
@Testcontainers
class RedriveIT {
    @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("postgresql.test.image", "postgres:18.6"));
    @Container private static final MySQLContainer MYSQL = new MySQLContainer(
            System.getProperty("mysql.test.image", "mysql:8.4.11"));

    @Test void postgresqlAcceptsOnlyCommittedNewGenerations() throws Exception {
        verify(new Database(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), JdbcMessagingDialect.POSTGRESQL));
    }

    @Test void mysqlAcceptsOnlyCommittedNewGenerations() throws Exception {
        verify(new Database(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), JdbcMessagingDialect.MYSQL));
    }

    private void verify(Database db) throws Exception {
        var receipts = new ArrayList<Map<String, Object>>();
        try (var logs = new Logs()) {
            sequential(db, logs, receipts);
            for (boolean commit : List.of(true, false)) {
                outerTransaction(db, logs, receipts, commit);
            }
            rollbackOnly(db, logs, receipts);
            concurrent(db, logs, receipts);
            rejected(db, logs, receipts);
            sqlFailure(db, logs, receipts);
            for (String creation : List.of("missing", "invalid")) {
                invalidCreation(db, logs, receipts, creation);
            }
            assertThat(String.join("", logs.lines)).doesNotContain(PRIVATE_BODY, PRIVATE_CAUSE, "management-user", "management-tenant");
            writeEvidence("redrive-" + db.name, receipts, logs);
        }
    }

    private void sequential(Database db, Logs logs, List<Map<String, Object>> receipts) {
        String id = seed(db, "twice");
        var wakes = new AtomicInteger();
        var root = new AtomicReference<Span>();
        var store = store(db, db.transactions, wakes, root);
        var original = fullRow(db);
        String previous = (String) db.row().get("publication_traceparent");
        for (int generation : List.of(8, 9)) {
            String token = token(db);
            var before = fullRow(db);
            assertThat(inManagement(id, () -> store.redrive(new MessageId(id), token))).isTrue();
            var after = fullRow(db);
            assertImmutable(before, after);
            assertThat(((Number) after.get("publication_generation")).intValue()).isEqualTo(generation);
            assertThat(((Number) after.get("attempt_count")).intValue()).isEqualTo(generation - 3);
            assertThat(after.get("status")).isEqualTo("PENDING");
            assertThat(after.get("next_attempt_at")).isNotEqualTo(before.get("next_attempt_at"));
            for (String cleared : List.of("terminal_at", "last_failure", "claim_token", "claim_expires_at")) {
                assertThat(after.get(cleared)).as(cleared).isNull();
            }
            assertThat(root.get().isRecording()).isFalse();
            var parent = (String) after.get("publication_traceparent");
            if (AGENT) {
                assertThat(parent).isNotBlank().isNotEqualTo(previous).isNotEqualTo(original.get("creation_traceparent"));
                assertThat(parent).contains(root.get().getSpanContext().getTraceId(), root.get().getSpanContext().getSpanId());
            } else {
                assertThat(parent).isNull();
                assertThat(after.get("publication_tracestate")).isNull();
            }
            receipt(receipts, db, id, "generation-" + generation, root.get(), wakes.get());
            previous = parent;
            if (generation == 8) { terminal(db, "second-token", 6); }
        }
        assertThat(wakes).hasValue(2);
        assertRecovery(logs, id, 2);
        assertImmutable(original, fullRow(db));
    }

    private void outerTransaction(Database db, Logs logs, List<Map<String, Object>> receipts, boolean commit) {
        String id = seed(db, commit ? "outer-commit" : "outer-rollback");
        var before = fullRow(db);
        var wakes = new AtomicInteger();
        var root = new AtomicReference<Span>();
        var store = store(db, db.transactions, wakes, root);
        String token = token(db);
        db.transactions.executeWithoutResult(status -> {
            assertThat(inManagement(id, () -> store.redrive(new MessageId(id), token))).isTrue();
            // The management span and scopes are already gone when the outer transaction decides.
            assertThat(ExecutionContextHolder.current()).isEmpty();
            assertThat(root.get().isRecording()).isEqualTo(AGENT);
            assertThat(fullRow(db)).isEqualTo(before);
            assertThat(wakes).hasValue(0);
            assertRecovery(logs, id, 0);
            assertThat(db.jdbc.queryForObject("SELECT publication_generation FROM moduvera_message_outbox", Integer.class)).isEqualTo(8);
            if (!commit) { status.setRollbackOnly(); }
        });
        assertThat(root.get().isRecording()).isFalse();
        assertThat(wakes).hasValue(commit ? 1 : 0);
        assertRecovery(logs, id, commit ? 1 : 0);
        if (commit) { assertImmutable(before, fullRow(db)); } else { assertThat(fullRow(db)).isEqualTo(before); }
        receipt(receipts, db, id, commit ? "outer-commit" : "outer-rollback", root.get(), wakes.get());
    }

    private void rollbackOnly(Database db, Logs logs, List<Map<String, Object>> receipts) {
        String id = seed(db, "owned-rollback-only");
        var before = fullRow(db);
        var wakes = new AtomicInteger();
        var root = new AtomicReference<Span>();
        TransactionOperations rollback = new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return db.transactions.execute(status -> {
                    T value = action.doInTransaction(status);
                    status.setRollbackOnly();
                    return value;
                });
            }
        };
        var store = store(db, rollback, wakes, root);
        String token = token(db);
        assertThat(inManagement(id, () -> store.redrive(new MessageId(id), token))).isFalse();
        assertThat(fullRow(db)).isEqualTo(before);
        assertThat(root.get().isRecording()).isFalse();
        assertThat(wakes).hasValue(0);
        assertRecovery(logs, id, 0);
        receipt(receipts, db, id, "owned-rollback-only", root.get(), wakes.get());
    }

    private void concurrent(Database db, Logs logs, List<Map<String, Object>> receipts) throws Exception {
        String id = seed(db, "race");
        var before = fullRow(db);
        var wakes = new AtomicInteger();
        var root = new AtomicReference<Span>();
        var store = store(db, db.transactions, wakes, root);
        String token = token(db);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int actor = 0; actor < 2; actor++) {
                String management = id + "-actor-" + actor;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    return inManagement(management, () -> store.redrive(new MessageId(id), token));
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.getFirst().get(30, TimeUnit.SECONDS), results.getLast().get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(wakes).hasValue(1);
        assertThat(((Number) db.row().get("publication_generation")).intValue()).isEqualTo(8);
        assertImmutable(before, fullRow(db));
        assertRecovery(logs, id, 1);
        receipt(receipts, db, id, "concurrent-one-accepted", root.get(), wakes.get());
    }

    private void rejected(Database db, Logs logs, List<Map<String, Object>> receipts) {
        String id = seed(db, "rejected");
        var before = fullRow(db);
        var wakes = new AtomicInteger();
        var root = new AtomicReference<Span>();
        var store = store(db, db.transactions, wakes, root);
        for (String token : List.of("", " ", "stale-token")) {
            assertThat(inManagement(id, () -> store.redrive(new MessageId(id), token))).isFalse();
        }
        assertThat(store.redrive(new MessageId(id), null)).isFalse();
        assertThat(store.redrive(new MessageId("missing-message"), token(db))).isFalse();
        String token = token(db);
        var readOnly = new org.springframework.transaction.support.TransactionTemplate(db.transactions.getTransactionManager());
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> inManagement(id, () -> readOnly.execute(status -> store.redrive(new MessageId(id), token))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("writable transaction");
        assertThat(fullRow(db)).isEqualTo(before);
        assertThat(root.get()).isNull();
        assertThat(wakes).hasValue(0);
        assertRecovery(logs, id, 0);
        receipts.add(Map.of("id", id, "phase", "rejections", "row", db.row(), "wakes", wakes.get()));
    }

    private void sqlFailure(Database db, Logs logs, List<Map<String, Object>> receipts) {
        String id = seed(db, "sql-failure");
        var before = fullRow(db);
        var wakes = new AtomicInteger();
        var root = new AtomicReference<Span>();
        var store = store(db, db.transactions, wakes, root);
        String token = token(db);
        db.jdbc.execute("ALTER TABLE moduvera_message_outbox ADD CONSTRAINT redrive_reject_generation CHECK (publication_generation = 7)");
        try {
            assertThatThrownBy(() -> inManagement(id, () -> store.redrive(new MessageId(id), token)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(fullRow(db)).isEqualTo(before);
            assertThat(root.get().isRecording()).isFalse();
            assertThat(wakes).hasValue(0);
            assertRecovery(logs, id, 0);
            receipt(receipts, db, id, "sql-rollback", root.get(), wakes.get());
        } finally {
            db.jdbc.execute("ALTER TABLE moduvera_message_outbox " + (db.name.equals("mysql") ? "DROP CHECK" : "DROP CONSTRAINT")
                    + " redrive_reject_generation");
        }
    }

    private void invalidCreation(Database db, Logs logs, List<Map<String, Object>> receipts, String creation) {
        String id = seed(db, creation + "-creation");
        db.jdbc.update("UPDATE moduvera_message_outbox SET creation_traceparent = ?, creation_tracestate = NULL",
                creation.equals("missing") ? null : "invalid-creation-parent");
        var before = fullRow(db);
        var wakes = new AtomicInteger();
        var root = new AtomicReference<Span>();
        var store = store(db, db.transactions, wakes, root);
        String token = token(db);
        assertThat(inManagement(id, () -> store.redrive(new MessageId(id), token))).isTrue();
        assertImmutable(before, fullRow(db));
        assertRecovery(logs, id, 1);
        receipt(receipts, db, id, creation + "-creation", root.get(), wakes.get());
    }

    private static JdbcOutboxStore store(Database db, TransactionOperations transactions, AtomicInteger wakes, AtomicReference<Span> root) {
        var jdbc = new NamedParameterJdbcTemplate(db.source) {
            @Override public <T> List<T> query(String sql, Map<String, ?> parameters, org.springframework.jdbc.core.RowMapper<T> mapper) {
                if (parameters.containsKey("redriveToken")) {
                    // Real SQL and row mapping follow; recovery diagnostics must never fetch the body.
                    assertThat(sql.toLowerCase(java.util.Locale.ROOT)).doesNotContain("*", "payload", "content_type");
                }
                return super.query(sql, parameters, mapper);
            }

            @Override public int update(String sql, Map<String, ?> parameters) {
                if (sql.contains("publication_generation = publication_generation + 1")) {
                    root.set(Span.current());
                    assertThat(Context.current().get(CONTEXT_KEY)).isNull();
                    assertThat(ExecutionContextHolder.require().correlationId()).startsWith("management-");
                    assertThat(Span.current().isRecording()).isEqualTo(AGENT);
                }
                return super.update(sql, parameters);
            }
        };
        return new JdbcOutboxStore(jdbc, JdbcMessagingDialect.valueOf(db.name.toUpperCase(java.util.Locale.ROOT)), transactions, wakes::incrementAndGet);
    }

    private static String seed(Database db, String operation) {
        String id = db.seed("redrive-" + operation);
        // A prior valid publication must not leak through the no-SDK recovery branch either.
        if (!AGENT) {
            db.jdbc.update("UPDATE moduvera_message_outbox SET publication_traceparent = ?, publication_tracestate = ?",
                    "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01", "vendor=old");
        }
        terminal(db, "first-token", 5);
        return id;
    }

    private static void terminal(Database db, String token, int failedAttempts) {
        db.jdbc.update("UPDATE moduvera_message_outbox SET status = 'TERMINAL', claim_token = ?, attempt_count = ?,"
                + " terminal_at = ?, last_failure = 'safe-failure', claim_expires_at = ?, next_attempt_at = ?", token, failedAttempts,
                java.sql.Timestamp.from(java.time.Instant.EPOCH), java.sql.Timestamp.from(java.time.Instant.EPOCH),
                java.sql.Timestamp.from(java.time.Instant.EPOCH));
    }

    private static String token(Database db) {
        return db.external.queryForObject("SELECT claim_token FROM moduvera_message_outbox", String.class);
    }

    private static Map<String, Object> fullRow(Database db) {
        var row = new HashMap<>(db.external.queryForMap("SELECT * FROM moduvera_message_outbox"));
        row.put("payload", HexFormat.of().formatHex((byte[]) row.get("payload")));
        return row;
    }

    private static void assertImmutable(Map<String, Object> before, Map<String, Object> after) {
        var left = new HashMap<>(before);
        var right = new HashMap<>(after);
        for (String field : List.of("status", "next_attempt_at", "terminal_at", "last_failure", "claim_token", "claim_expires_at",
                "publication_generation", "publication_traceparent", "publication_tracestate", "attempt_count")) {
            left.remove(field);
            right.remove(field);
        }
        assertThat(right).isEqualTo(left);
    }

    private static void assertRecovery(Logs logs, String id, int count) {
        var events = logs.events(id, "outbox.recovery");
        assertThat(events).hasSize(count);
        for (var event : events) {
            assertThat(event.path("log").path("level").asString()).isEqualTo("WARN");
            assertThat(event.path("correlation_id").asString()).isEqualTo("original-" + id);
            assertThat(event.path("tenant_id").asString()).isEqualTo("origin-tenant");
            assertThat(event.has("event")).isFalse();
            assertThat(event.has("error")).isFalse();
        }
    }

    private static void receipt(List<Map<String, Object>> receipts, Database db, String id, String phase, Span span, int wakes) {
        receipts.add(Map.of("id", id, "phase", phase, "row", db.row(), "rootTrace", span.getSpanContext().getTraceId(),
                "rootSpan", span.getSpanContext().getSpanId(), "recordingAfterCompletion", span.isRecording(), "wakes", wakes,
                "allImmutableColumnsAndPayloadVerified", true));
    }
}
