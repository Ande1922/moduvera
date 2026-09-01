package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.outbox.OutboxPublishReport;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.github.ande1922.moduvera.message.publication.DurablePublicationTransactionException;
import java.net.URI;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcOutboxWorkerAdministrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcOutboxStore outbox;
    private JdbcOutboxStore secondOutbox;
    private JdbcDurablePublication publication;
    private TransactionTemplate transactions;
    private AtomicInteger wakeSignals;

    @BeforeAll
    void setUp() throws Exception {
        dataSource = postgresDataSource();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "db/moduvera-messaging/postgresql/V1__create_moduvera_messaging.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE test_outbox_business_record (id VARCHAR(128) PRIMARY KEY)");
        transactions = transactionTemplate(dataSource);
        wakeSignals = new AtomicInteger();
        var named = new NamedParameterJdbcTemplate(dataSource);
        outbox = new JdbcOutboxStore(
                named, JdbcMessagingDialect.POSTGRESQL, transactions, wakeSignals::incrementAndGet);
        secondOutbox = new JdbcOutboxStore(
                named, JdbcMessagingDialect.POSTGRESQL, transactions, () -> {});
        publication = new JdbcDurablePublication(dataSource, outbox);
    }

    @BeforeEach
    void clearTables() {
        jdbc.execute("TRUNCATE TABLE moduvera_message_outbox, test_outbox_business_record");
        wakeSignals.set(0);
    }

    @Test
    void durablePublicationUsesTheCommittedWritableTransactionBoundToItsDataSource() {
        assertThatThrownBy(() -> publication.append(message("missing-tx", "missing")))
                .isInstanceOf(DurablePublicationTransactionException.class)
                .hasMessageContaining("active local database transaction");

        TransactionTemplate readOnly = transactionTemplate(dataSource);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(
                        ignored -> publication.append(message("read-only", "read-only"))))
                .isInstanceOf(DurablePublicationTransactionException.class)
                .hasMessageContaining("writable");

        DataSource otherDataSource = postgresDataSource();
        TransactionTemplate otherTransactions = transactionTemplate(otherDataSource);
        assertThatThrownBy(() -> otherTransactions.executeWithoutResult(
                        ignored -> publication.append(message("wrong-source", "wrong-source"))))
                .isInstanceOf(DurablePublicationTransactionException.class)
                .hasMessageContaining("outbox DataSource");

        transactions.executeWithoutResult(ignored -> {
            jdbc.update("INSERT INTO test_outbox_business_record(id) VALUES (?)", "committed");
            publication.append(message("committed", "committed"));
            assertThat(wakeSignals).hasValue(0);
        });
        assertThat(wakeSignals).hasValue(1);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
                    jdbc.update("INSERT INTO test_outbox_business_record(id) VALUES (?)", "rolled-back");
                    publication.append(message("rolled-back", "rolled-back"));
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(wakeSignals).hasValue(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM test_outbox_business_record", Integer.class))
                .isEqualTo(1);
        assertThat(outbox.backlog().pendingCount()).isEqualTo(1);
    }

    @Test
    void workerPersistsRetryAttemptsAndTerminalOutcomes() {
        append(message("retry", "retry"));
        AtomicInteger retrySends = new AtomicInteger();
        var retryWorker = worker(ignored -> {
            if (retrySends.incrementAndGet() == 1) {
                throw new IllegalStateException("broker unavailable");
            }
        }, 3);

        assertThat(retryWorker.publishBatch(1))
                .isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));
        var retryClaim = outbox.claim(1, Duration.ofSeconds(30)).orElseThrow();
        assertThat(retryClaim.messages().getFirst().failedAttempts()).isEqualTo(1);
        jdbc.update(
                "UPDATE moduvera_message_outbox SET claim_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE message_id = ?",
                "retry");
        assertThat(retryWorker.publishBatch(1))
                .isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 0));
        assertThat(retrySends).hasValue(2);

        append(message("terminal", "terminal"));
        var terminalWorker = worker(ignored -> {
            throw new NonRetryableMessageException("invalid route");
        }, 3);
        assertThat(terminalWorker.publishBatch(1))
                .isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));

        var terminal = outbox.findTerminal(10).getFirst();
        assertThat(terminal.id()).isEqualTo(new MessageId("terminal"));
        assertThat(terminal.safeFailure()).isEqualTo("NonRetryableMessageException");
        assertThat(outbox.backlog().terminalCount()).isEqualTo(1);
    }

    @Test
    void interruptedUnknownSendConsumesNoAttemptAndRemainsEligibleAfterLeaseTakeover() {
        append(message("unknown", "unknown"));
        var interruptedWorker = worker(ignored -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(new InterruptedException("forced stop"));
        }, 3);

        try {
            assertThat(interruptedWorker.publishBatch(1))
                    .isEqualTo(new OutboxPublishReport(1, 0, 0, 1, 0));
        } finally {
            Thread.interrupted();
        }
        assertThat(outbox.backlog().pendingCount()).isEqualTo(1);

        jdbc.update(
                "UPDATE moduvera_message_outbox SET claim_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE message_id = ?",
                "unknown");
        var reclaimed = secondOutbox.claim(1, Duration.ofSeconds(30)).orElseThrow();
        assertThat(reclaimed.messages().getFirst().message().descriptor().id())
                .isEqualTo(new MessageId("unknown"));
        assertThat(reclaimed.messages().getFirst().failedAttempts()).isZero();
    }

    @Test
    void leaseTakeoverFencesTheOriginalWorkerCompletion() {
        append(message("takeover", "takeover"));
        AtomicInteger sends = new AtomicInteger();
        var takeover = new OutboxWorker(
                secondOutbox,
                ignored -> sends.incrementAndGet(),
                Clock.systemUTC(),
                Duration.ofSeconds(30),
                Duration.ZERO,
                3);
        var original = worker(ignored -> {
            sends.incrementAndGet();
            jdbc.update(
                    "UPDATE moduvera_message_outbox SET claim_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE message_id = ?",
                    "takeover");
            assertThat(takeover.publishBatch(1).published()).isEqualTo(1);
        }, 3);

        assertThat(original.publishBatch(1))
                .isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 1));
        assertThat(sends).hasValue(2);
        assertThat(outbox.backlog().pendingCount()).isZero();
    }

    @Test
    void administrationRedrivesWithFencingAndCleansPublishedRowsInBoundedBatches() {
        append(message("ordered-1", "ordered"));
        append(message("ordered-2", "ordered"));
        append(message("published-1", "published-1"));
        append(message("published-2", "published-2"));
        append(message("pending", "pending"));
        var batch = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(batch.messages())
                .extracting(entry -> entry.message().descriptor().id().value())
                .containsExactlyInAnyOrder("ordered-1", "published-1", "published-2", "pending");
        assertThat(outbox.markTerminal(
                        new MessageId("ordered-1"),
                        batch.claimToken(),
                        Instant.now(),
                        "InvalidPayload"))
                .isTrue();
        assertThat(outbox.markPublished(
                        new MessageId("published-1"), batch.claimToken(), Instant.EPOCH))
                .isTrue();
        assertThat(outbox.markPublished(
                        new MessageId("published-2"), batch.claimToken(), Instant.EPOCH))
                .isTrue();

        var terminal = outbox.findTerminal(10).getFirst();
        assertThat(terminal.id()).isEqualTo(new MessageId("ordered-1"));
        assertThat(outbox.redrive(terminal.id(), "stale-token")).isFalse();
        assertThat(outbox.redrive(terminal.id(), terminal.redriveToken())).isTrue();

        var redriven = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(redriven.messages())
                .extracting(entry -> entry.message().descriptor().id().value())
                .containsExactly("ordered-1");
        SerializedMessage redrivenMessage = redriven.messages().getFirst().message();
        assertThat(redrivenMessage.descriptor().id()).isEqualTo(new MessageId("ordered-1"));
        assertThat(redrivenMessage.descriptor().type().value())
                .isEqualTo("io.github.ande1922.moduvera.reference.inventory.reserve.v1");
        assertThat(redrivenMessage.descriptor().destination())
                .isEqualTo(new Destination("inventory.commands"));
        assertThat(redrivenMessage.descriptor().partitionKey()).isEqualTo("tenant-a:ordered");
        assertThat(redrivenMessage.payload()).containsExactly("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(redriven.messages().getFirst().failedAttempts()).isEqualTo(1);
        assertThat(outbox.findTerminal(10)).isEmpty();

        assertThat(outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 1)).isEqualTo(1);
        assertThat(outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 1)).isEqualTo(1);
        assertThat(outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 1)).isZero();
        assertThat(outbox.backlog().pendingCount()).isEqualTo(3);
    }

    private OutboxWorker worker(
            io.github.ande1922.moduvera.message.outbox.MessageTransport transport,
            int maxAttempts) {
        return new OutboxWorker(
                outbox,
                transport,
                Clock.systemUTC(),
                System::nanoTime,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                maxAttempts,
                PublicationObserver.noop());
    }

    private void append(SerializedMessage message) {
        transactions.executeWithoutResult(ignored -> publication.append(message));
    }

    private DataSource postgresDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static TransactionTemplate transactionTemplate(DataSource source) {
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private static SerializedMessage message(String id, String partitionKey) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        MessageKind.ASYNC_COMMAND,
                        new MessageType(
                                "io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                        URI.create("urn:service:order"),
                        new Destination("inventory.commands"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "order-service"),
                        "corr-outbox-it",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        "tenant-a:" + partitionKey),
                "{}");
    }
}
