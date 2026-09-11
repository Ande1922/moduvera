package io.github.ande1922.moduvera.messaging.kafka;

import static io.github.ande1922.moduvera.messaging.kafka.RelayFixtureSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.message.MessageId;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** A JDBC-only management JVM killed after the real commit, before its short root can end/export. */
public final class RedriveFixtureChild {
    private RedriveFixtureChild() {}

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(System.getenv("RELAY_FIXTURE_DIRECTORY"));
        var db = new Database(System.getenv("RELAY_FIXTURE_JDBC_URL"), System.getenv("RELAY_FIXTURE_JDBC_USER"),
                System.getenv("RELAY_FIXTURE_JDBC_PASSWORD"), JdbcMessagingDialect.POSTGRESQL);
        String id = db.jdbc.queryForObject("SELECT message_id FROM moduvera_message_outbox", String.class);
        String token = db.jdbc.queryForObject("SELECT claim_token FROM moduvera_message_outbox", String.class);
        var store = new JdbcOutboxStore(new NamedParameterJdbcTemplate(db.source), JdbcMessagingDialect.POSTGRESQL, db.transactions, () -> {
            var row = db.row();
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(((Number) row.get("publication_generation")).intValue()).isEqualTo(1);
            try {
                write(directory.resolve("redrive-committed.json"), Map.of("pid", ProcessHandle.current().pid(),
                        "checkpoint", "committed-before-root-end", "row", row));
                new CountDownLatch(1).await();
                throw new AssertionError("redrive crash checkpoint must be terminated by the parent");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } catch (Exception failure) {
                throw new IllegalStateException("cannot write redrive checkpoint", failure);
            }
        });
        try (var logs = new Logs(directory.resolve("redrive-logs.jsonl"))) {
            inManagement(id, () -> store.redrive(new MessageId(id), token));
        }
        throw new AssertionError("redrive fixture must stop at the committed wake callback");
    }
}
