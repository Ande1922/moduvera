package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.ande1922.moduvera.messaging.kafka.RelayFixtureSupport.*;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.message.outbox.OutboxPublishReport;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/** Actual JDBC dispositions and deterministic transport fault windows; real Kafka is in RelayProcessIT. */
@Testcontainers
class RelayPublicationIT {
    @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("postgresql.test.image", "postgres:18.6"));
    @Container private static final MySQLContainer MYSQL = new MySQLContainer(
            System.getProperty("mysql.test.image", "mysql:8.4.11"));

    @Test void postgresqlRestoresPublicationAndFencesEveryCompletion() throws Exception {
        verify(new Database(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), JdbcMessagingDialect.POSTGRESQL));
    }

    @Test void mysqlRestoresPublicationAndFencesEveryCompletion() throws Exception {
        verify(new Database(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), JdbcMessagingDialect.MYSQL));
    }

    private void verify(Database db) throws Exception {
        var receipts = new ArrayList<Map<String, Object>>();
        try (var logs = new Logs()) {
            successAndRetry(db, logs, receipts);
            terminal(db, logs, receipts);
            for (String mode : List.of("success", "retry", "terminal")) {
                stale(db, logs, receipts, mode);
                writeFailure(db, logs, receipts, mode);
            }
            interrupted(db, logs, receipts);
            errorAndInternal(db, logs, receipts);
            if (AGENT) { unsampled(db, logs, receipts); }
            assertThat(String.join("", logs.lines)).doesNotContain(PRIVATE_BODY, PRIVATE_CAUSE, "management-user", "management-tenant");
            writeEvidence("relay-" + db.name, receipts, logs);
        }
    }

    private void successAndRetry(Database db, Logs logs, List<Map<String, Object>> receipts) {
        for (boolean retry : List.of(false, true)) {
            String id = db.seed(retry ? "retry-success" : "first-success");
            var before = db.row();
            var sends = new AtomicInteger();
            MessageTransport transport = message -> {
                sending(db, receipts, id);
                if (sends.getAndIncrement() == 0 && retry) { throw new IllegalStateException(PRIVATE_CAUSE); }
            };
            var worker = worker(db.store, transport, 3);
            if (retry) {
                assertThat(inManagement(id, () -> worker.publishBatch(1))).isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));
                assertThat(db.row().get("status")).isEqualTo("PENDING");
            }
            assertThat(inManagement(id, () -> worker.publishBatch(1))).isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 0));
            assertPair(db.row(), before);
            assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(retry ? 1 : 0);
            assertThat(logs.events(id, "task.execute")).hasSize(retry ? 2 : 1);
            var canonical = logs.events(id, "task.execute").getLast();
            assertCanonical(canonical, id, "success", "success", "published");
            assertThat(canonical.path("outbox_failed_attempts").asInt()).isEqualTo(retry ? 1 : 0);
            assertThat(logs.events(id, "outbox.recovery")).hasSize(retry ? 1 : 0);
            assertThat(logs.events(id, "outbox.publish.failure")).isEmpty();
            receipts.add(Map.of("phase", "complete", "id", id, "row", db.row(), "sends", sends.get()));
        }
    }

    private void terminal(Database db, Logs logs, List<Map<String, Object>> receipts) {
        String id = db.seed("terminal");
        var worker = worker(db.store, message -> { sending(db, receipts, id); throw new IllegalStateException(PRIVATE_CAUSE); }, 2);
        for (int i = 0; i < 2; i++) {
            assertThat(inManagement(id, () -> worker.publishBatch(1))).isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));
        }
        assertThat(db.row().get("status")).isEqualTo("TERMINAL");
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isEqualTo(2);
        assertThat(logs.events(id, "outbox.recovery")).hasSize(1);
        var terminal = logs.events(id, "outbox.publish.failure");
        assertThat(terminal).hasSize(1);
        assertThat(terminal.getFirst().path("error").path("code").asString()).isEqualTo("DEP_OUTBOX_PUBLICATION_FAILED");
        assertThat(terminal.getFirst().has("event")).isFalse();
        assertThat(terminal.getFirst().has("duration_ms")).isFalse();
        assertThat(terminal.getFirst().has("retry")).isFalse();
        assertThat(terminal.getFirst().path("error").path("stack_trace").asString()).isNotBlank();
        assertCanonical(logs.events(id, "task.execute").getLast(), id, "failure", "failure", "terminal");
        receipts.add(Map.of("phase", "terminal", "id", id, "row", db.row()));
    }

    private void stale(Database db, Logs logs, List<Map<String, Object>> receipts, String mode) {
        String id = db.seed("stale-" + mode);
        var before = db.row();
        var worker = worker(db.store, message -> {
            sending(db, receipts, id);
            db.expire();
            var replacement = db.store.claim(1, Duration.ofMinutes(1)).orElseThrow();
            assertThat(db.store.preparePublication(replacement.messages().getFirst(), replacement.claimToken())).isPresent();
            if (!mode.equals("success")) { throw new IllegalStateException(PRIVATE_CAUSE); }
        }, mode.equals("terminal") ? 1 : 2);
        var report = inManagement(id, () -> worker.publishBatch(1));
        assertThat(report.staleUpdates()).isEqualTo(1);
        assertThat(db.row().get("status")).isEqualTo("PENDING");
        assertThat(((Number) db.row().get("attempt_count")).intValue()).isZero();
        assertPair(db.row(), before);
        assertCanonical(logs.events(id, "task.execute").getFirst(), id, "failure", mode.equals("success") ? "success" : "failure", "stale");
        assertThat(logs.events(id, "outbox.recovery")).isEmpty();
        assertThat(logs.events(id, "outbox.publish.failure")).isEmpty();
        receipts.add(Map.of("phase", "stale", "id", id, "mode", mode, "row", db.row()));
        db.expire();
        assertThat(inManagement(id, () -> worker(db.store, message -> sending(db, receipts, id), 2).publishBatch(1)).published()).isEqualTo(1);
        assertPair(db.row(), before);
    }

    private void writeFailure(Database db, Logs logs, List<Map<String, Object>> receipts, String mode) {
        String id = db.seed("write-failure-" + mode);
        var before = db.row();
        String expression = mode.equals("success") ? "status <> 'PUBLISHED'" : mode.equals("terminal") ? "status <> 'TERMINAL'" : "attempt_count = 0";
        db.jdbc.execute("ALTER TABLE moduvera_message_outbox ADD CONSTRAINT relay_write_fault CHECK (" + expression + ")");
        try {
            var worker = worker(db.store, message -> {
                sending(db, receipts, id);
                if (!mode.equals("success")) { throw new IllegalStateException(PRIVATE_CAUSE); }
            }, mode.equals("terminal") ? 1 : 2);
            assertThatThrownBy(() -> inManagement(id, () -> worker.publishBatch(1))).isInstanceOf(DataAccessException.class);
            assertThat(db.row().get("status")).isEqualTo("PENDING");
            assertThat(((Number) db.row().get("attempt_count")).intValue()).isZero();
            assertPair(db.row(), before);
            assertCanonical(logs.events(id, "task.execute").getFirst(), id, "failure", mode.equals("success") ? "success" : "failure", "failed");
            assertThat(logs.events(id, "outbox.recovery")).isEmpty();
            assertThat(logs.events(id, "outbox.publish.failure")).isEmpty();
            receipts.add(Map.of("phase", "write-failure", "id", id, "mode", mode, "row", db.row()));
        } finally {
            db.jdbc.execute("ALTER TABLE moduvera_message_outbox DROP " + (db.name.equals("mysql") ? "CHECK" : "CONSTRAINT") + " relay_write_fault");
        }
        db.expire();
        assertThat(inManagement(id, () -> worker(db.store, message -> sending(db, receipts, id), 2).publishBatch(1)).published()).isEqualTo(1);
        assertPair(db.row(), before);
    }

    private void interrupted(Database db, Logs logs, List<Map<String, Object>> receipts) {
        String id = db.seed("interrupted");
        var before = db.row();
        var worker = worker(db.store, message -> {
            sending(db, receipts, id);
            throw new IllegalStateException(new InterruptedException(PRIVATE_CAUSE));
        }, 2);
        try {
            assertThat(inManagement(id, () -> worker.publishBatch(1))).isEqualTo(new OutboxPublishReport(1, 0, 0, 1, 0));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(db.row()).isEqualTo(before);
        var canonical = logs.events(id, "task.execute").getFirst();
        assertCanonical(canonical, id, "failure", "failure", "not_attempted");
        assertThat(canonical.path("termination_reason").asString()).isEqualTo("interrupted");
        assertThat(logs.events(id, "outbox.recovery")).isEmpty();
        assertThat(logs.events(id, "outbox.publish.failure")).isEmpty();
        receipts.add(Map.of("phase", "interrupted", "id", id, "row", db.row()));
    }

    private void errorAndInternal(Database db, Logs logs, List<Map<String, Object>> receipts) {
        String id = db.seed("error");
        var before = db.row();
        var failure = new AssertionError(PRIVATE_CAUSE);
        var worker = worker(db.store, message -> { sending(db, receipts, id); throw failure; }, 2);
        assertThatThrownBy(() -> inManagement(id, () -> worker.publishBatch(1))).isSameAs(failure);
        assertThat(db.row()).isEqualTo(before);
        assertCanonical(logs.events(id, "task.execute").getFirst(), id, "failure", "failure", "not_attempted");
        assertThat(logs.events(id, "outbox.publish.failure")).isEmpty();
        assertThat(logs.events(id, "outbox.recovery")).isEmpty();
        receipts.add(Map.of("phase", "error", "id", id, "row", db.row()));

        String internal = db.seed("internal");
        var internalWorker = new io.github.ande1922.moduvera.message.outbox.OutboxWorker(db.store,
                message -> sending(db, receipts, internal), java.time.Clock.systemUTC(), System::nanoTime,
                Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ZERO, 2,
                io.github.ande1922.moduvera.message.outbox.PublicationObserver.noop(), new RelayPublicationLifecycle(java.util.Set.of()));
        assertThat(inManagement(internal, () -> internalWorker.publishBatch(1)).published()).isEqualTo(1);
        assertThat(logs.events(internal, "mq.produce")).isEmpty();
        assertCanonical(logs.events(internal, "task.execute").getFirst(), internal, "success", "success", "published");
        receipts.add(Map.of("phase", "internal", "id", internal, "row", db.row()));
    }

    private void unsampled(Database db, Logs logs, List<Map<String, Object>> receipts) {
        String id = db.seed("unsampled");
        String parent = (String) db.row().get("publication_traceparent");
        db.jdbc.update("UPDATE moduvera_message_outbox SET publication_traceparent = ?", parent.substring(0, parent.length() - 2) + "00");
        var before = db.row();
        var worker = worker(db.store, message -> {
            sending(db, receipts, id);
            assertThat(io.opentelemetry.api.trace.Span.current().getSpanContext().isSampled()).isFalse();
        }, 2);
        assertThat(inManagement(id, () -> worker.publishBatch(1)).published()).isEqualTo(1);
        assertPair(db.row(), before);
        assertCanonical(logs.events(id, "task.execute").getFirst(), id, "success", "success", "published");
        receipts.add(Map.of("phase", "unsampled", "id", id, "row", db.row()));
    }

    private static void sending(Database db, List<Map<String, Object>> receipts, String id) {
        assertThat(Context.current().get(CONTEXT_KEY)).isNull();
        assertThat(ExecutionContextHolder.require().correlationId()).isEqualTo("management-" + id);
        var trace = trace();
        if (AGENT) {
            assertThat(trace.get("valid")).isEqualTo(true);
            assertThat(trace.get("trace")).isEqualTo(((String) db.row().get("publication_traceparent")).split("-")[1]);
            assertThat(trace.get("span")).isNotEqualTo(((String) db.row().get("publication_traceparent")).split("-")[2]);
        } else {
            assertThat(trace.get("valid")).isEqualTo(false);
        }
        var receipt = new HashMap<String, Object>();
        receipt.put("phase", "send-probe"); receipt.put("id", id); receipt.put("trace", trace); receipt.put("row", db.row());
        receipts.add(receipt);
    }

    private static void assertPair(Map<String, Object> actual, Map<String, Object> before) {
        for (String key : List.of("creation_traceparent", "creation_tracestate", "publication_traceparent", "publication_tracestate", "publication_generation")) {
            assertThat(actual.get(key)).as(key).isEqualTo(before.get(key));
        }
    }

    private static void assertCanonical(JsonNode event, String id, String outcome, String transport, String write) {
        assertThat(event.path("event").path("outcome").asString()).isEqualTo(outcome);
        assertThat(event.path("transport_result").asString()).isEqualTo(transport);
        assertThat(event.path("outbox_write_result").asString()).isEqualTo(write);
        assertThat(event.path("correlation_id").asString()).isEqualTo("original-" + id);
        assertThat(event.path("tenant_id").asString()).isEqualTo("origin-tenant");
        assertThat(event.path("actor_id").asString()).isEqualTo("origin-service");
        assertThat(event.path("initiator_id").asString()).isEqualTo("origin-user");
        assertThat(event.path("duration_ms").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(event.path("retry").isMissingNode()).isTrue();
        assertThat(event.path("log").path("level").asString()).isEqualTo("INFO");
    }
}
