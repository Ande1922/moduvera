package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.NestedTransactionBoundaryException;
import io.github.ande1922.moduvera.data.spring.SpringTransactionBoundary;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.InboundMessageContract;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.NonRetryableMessageException;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxOutcome;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxPublishReport;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.github.ande1922.moduvera.message.publication.DurablePublicationTransactionException;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcMessagingStoreIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcOutboxStore outbox;
    private JdbcOutboxStore secondOutbox;
    private JdbcDurablePublication publication;
    private JdbcInboxRepository inbox;
    private ReliableMessageConsumerFactory inboxConsumers;
    private SpringTransactionBoundary inboxTransactions;
    private TransactionTemplate transactions;
    private AtomicInteger wakeSignals;

    @BeforeAll
    void setUp() {
        dataSource = postgresDataSource();
        new DatabaseMigrator(dataSource)
                .migrate(new MigrationPlan(
                        new DatabaseComponent("messaging"),
                        List.of("classpath:db/moduvera-messaging/postgresql"),
                        true));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE test_business_record (id VARCHAR(128) PRIMARY KEY)");
        var named = new NamedParameterJdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var signal = new LocalOutboxWakeSignal();
        wakeSignals = new AtomicInteger();
        signal.listen(wakeSignals::incrementAndGet);
        outbox = new JdbcOutboxStore(named, JdbcMessagingDialect.POSTGRESQL, transactions, signal);
        publication = new JdbcDurablePublication(dataSource, outbox);
        secondOutbox = new JdbcOutboxStore(
                named, JdbcMessagingDialect.POSTGRESQL, transactions, new LocalOutboxWakeSignal());
        inbox = new JdbcInboxRepository(named);
        inboxTransactions = new SpringTransactionBoundary(transactions);
        inboxConsumers = new ReliableMessageConsumerFactory(new KafkaMessageMapper());
        jdbc.execute("""
                CREATE TABLE test_inbox_business_record (
                    tenant_id VARCHAR(64) NOT NULL,
                    consumer_id VARCHAR(128) NOT NULL,
                    message_id VARCHAR(128) NOT NULL,
                    PRIMARY KEY (tenant_id, consumer_id, message_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE test_deferred_commit_record (
                    id INTEGER UNIQUE DEFERRABLE INITIALLY DEFERRED
                )
                """);
    }

    @BeforeEach
    void clearTables() {
        jdbc.execute("""
                TRUNCATE TABLE moduvera_message_inbox, moduvera_message_outbox,
                    test_business_record, test_inbox_business_record,
                    test_deferred_commit_record
                """);
        wakeSignals.set(0);
    }

    @Test
    void signalsOnlyAfterTheBusinessAndOutboxTransactionCommits() {
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("INSERT INTO test_business_record(id) VALUES (?)", "business-commit");
            append(message("msg-committed"));
            assertThat(wakeSignals).hasValue(0);
        });
        assertThat(wakeSignals).hasValue(1);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
                    jdbc.update("INSERT INTO test_business_record(id) VALUES (?)", "business-rollback");
                    append(message("msg-rollback"));
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(wakeSignals).hasValue(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM test_business_record WHERE id = ?",
                        Integer.class,
                        "business-rollback"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM moduvera_message_outbox WHERE message_id = ?",
                        Integer.class,
                        "msg-rollback"))
                .isZero();
    }

    @Test
    void durablePublicationRejectsMissingReadOnlyAndWrongDataSourceTransactions() {
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

        assertThat(outbox.backlog().pendingCount()).isZero();
        assertThat(wakeSignals).hasValue(0);
    }

    @Test
    void claimsWithDatabaseTimeAndOneTokenForTheWholeBatch() {
        append(message("msg-a", "destination-a", "order-a"));
        append(message("msg-b", "destination-a", "order-b"));

        var batch = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();

        assertThat(batch.messages()).hasSize(2);
        assertThat(batch.claimToken()).isNotBlank();
        assertThat(batch.messages()).allMatch(entry -> entry.failedAttempts() == 0);
        assertThat(batch.messages().getFirst().message().descriptor().actor().permissions()).isEmpty();
    }

    @Test
    void twoStoresAtomicallyDivideClaimableScopes() throws Exception {
        for (int index = 0; index < 20; index++) {
            append(message("msg-" + index, "destination-a", "order-" + index));
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> claimIds(outbox, ready, start));
            var second = workers.submit(() -> claimIds(secondOutbox, ready, start));
            ready.await();
            start.countDown();

            Set<String> firstIds = first.get();
            Set<String> secondIds = second.get();
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
            Set<String> all = new HashSet<>(firstIds);
            all.addAll(secondIds);
            assertThat(all).hasSize(20);
        }
    }

    @Test
    void ordersWithinDestinationAndPartitionKeyButAllowsOtherScopes() {
        append(message("msg-a1", "destination-a", "shared-key"));
        append(message("msg-a2", "destination-a", "shared-key"));
        append(message("msg-b1", "destination-b", "shared-key"));
        append(message("msg-c1", "destination-a", "other-key"));

        var first = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(ids(first)).containsExactlyInAnyOrder("msg-a1", "msg-b1", "msg-c1");
        first.messages().forEach(entry -> assertThat(outbox.markPublished(
                        entry.message().descriptor().id(), first.claimToken(), Instant.now()))
                .isTrue());

        assertThat(ids(outbox.claim(10, Duration.ofSeconds(30)).orElseThrow()))
                .containsExactly("msg-a2");
    }

    @Test
    void skipLockedNeverLetsASuccessorBypassItsVisiblePredecessor() throws Exception {
        append(message("msg-a1", "destination-a", "shared-key"));
        append(message("msg-a2", "destination-a", "shared-key"));
        CountDownLatch predecessorLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        try (var holder = Executors.newSingleThreadExecutor()) {
            var lock = holder.submit(() -> transactions.executeWithoutResult(ignored -> {
                jdbc.queryForObject(
                        "SELECT message_id FROM moduvera_message_outbox WHERE message_id = ? FOR UPDATE",
                        String.class,
                        "msg-a1");
                predecessorLocked.countDown();
                try {
                    releaseLock.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            predecessorLocked.await();

            assertThat(secondOutbox.claim(10, Duration.ofSeconds(30))).isEmpty();

            releaseLock.countDown();
            lock.get();
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedWithoutConsumingAnAttemptAndOldTokenIsFenced() {
        append(message("msg-lease"));
        var abandoned = outbox.claim(1, Duration.ofSeconds(30)).orElseThrow();
        jdbc.update(
                "UPDATE moduvera_message_outbox SET claim_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE message_id = ?",
                "msg-lease");

        var reclaimed = secondOutbox.claim(1, Duration.ofSeconds(30)).orElseThrow();

        assertThat(reclaimed.messages().getFirst().failedAttempts()).isZero();
        assertThat(reclaimed.claimToken()).isNotEqualTo(abandoned.claimToken());
        assertThat(outbox.markPublished(
                        new MessageId("msg-lease"), abandoned.claimToken(), Instant.now()))
                .isFalse();
        assertThat(outbox.markFailed(
                        new MessageId("msg-lease"),
                        abandoned.claimToken(),
                        Duration.ZERO,
                        "Timeout"))
                .isFalse();
        assertThat(outbox.markTerminal(
                        new MessageId("msg-lease"),
                        abandoned.claimToken(),
                        Instant.now(),
                        "Poison"))
                .isFalse();
        assertThat(secondOutbox.markPublished(
                        new MessageId("msg-lease"), reclaimed.claimToken(), Instant.now()))
                .isTrue();
    }

    @Test
    void retryFailureBlocksItsSuccessorAndIncrementsOnlyAfterTheFailure() {
        append(message("msg-a1", "destination-a", "shared-key"));
        append(message("msg-a2", "destination-a", "shared-key"));
        var first = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(ids(first)).containsExactly("msg-a1");
        assertThat(outbox.markFailed(
                        new MessageId("msg-a1"), first.claimToken(), Duration.ZERO, "Timeout"))
                .isTrue();

        var retry = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(ids(retry)).containsExactly("msg-a1");
        assertThat(retry.messages().getFirst().failedAttempts()).isEqualTo(1);
    }

    @Test
    void workerPersistsRetryAttemptsAndTerminalOutcomes() {
        append(message("worker-retry", "worker-retry"));
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
        expireClaim("worker-retry");
        assertThat(retryWorker.publishBatch(1))
                .isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 0));
        assertThat(retrySends).hasValue(2);

        append(message("worker-terminal", "worker-terminal"));
        var terminalWorker = worker(ignored -> {
            throw new NonRetryableMessageException("invalid route");
        }, 3);
        assertThat(terminalWorker.publishBatch(1))
                .isEqualTo(new OutboxPublishReport(1, 0, 1, 0, 0));

        var terminal = outbox.findTerminal(10).getFirst();
        assertThat(terminal.id()).isEqualTo(new MessageId("worker-terminal"));
        assertThat(terminal.safeFailure()).isEqualTo("NonRetryableMessageException");
        assertThat(outbox.backlog().terminalCount()).isEqualTo(1);
    }

    @Test
    void interruptedUnknownSendConsumesNoAttemptAndRemainsEligibleAfterLeaseTakeover() {
        append(message("worker-unknown", "worker-unknown"));
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

        expireClaim("worker-unknown");
        var reclaimed = secondOutbox.claim(1, Duration.ofSeconds(30)).orElseThrow();
        assertThat(reclaimed.messages().getFirst().message().descriptor().id())
                .isEqualTo(new MessageId("worker-unknown"));
        assertThat(reclaimed.messages().getFirst().failedAttempts()).isZero();
    }

    @Test
    void leaseTakeoverFencesTheOriginalWorkerCompletion() {
        append(message("worker-takeover", "worker-takeover"));
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
            expireClaim("worker-takeover");
            assertThat(takeover.publishBatch(1).published()).isEqualTo(1);
        }, 3);

        assertThat(original.publishBatch(1))
                .isEqualTo(new OutboxPublishReport(1, 1, 0, 0, 1));
        assertThat(sends).hasValue(2);
        assertThat(outbox.backlog().pendingCount()).isZero();
    }

    @Test
    void terminalCanBeQueriedAndFencedRedrivePreservesTheMessageIdAndReleasesSuccessor() {
        append(message("msg-a1", "destination-a", "shared-key"));
        append(message("msg-a2", "destination-a", "shared-key"));
        var poison = outbox.claim(1, Duration.ofSeconds(30)).orElseThrow();
        assertThat(outbox.markTerminal(
                        new MessageId("msg-a1"), poison.claimToken(), Instant.now(), "InvalidSchema"))
                .isTrue();

        var terminal = outbox.findTerminal(10).getFirst();
        assertThat(terminal.id()).isEqualTo(new MessageId("msg-a1"));
        assertThat(outbox.redrive(terminal.id(), "stale-token")).isFalse();
        assertThat(outbox.claim(10, Duration.ofSeconds(30)).orElseThrow().messages())
                .extracting(entry -> entry.message().descriptor().id().value())
                .containsExactly("msg-a2");

        assertThat(outbox.redrive(terminal.id(), terminal.redriveToken())).isTrue();
        jdbc.update("UPDATE moduvera_message_outbox SET claim_expires_at = NULL WHERE message_id = ?", "msg-a2");
        var redriven = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(ids(redriven)).contains("msg-a1");
        assertThat(redriven.messages().stream()
                        .filter(entry -> entry.message().descriptor().id().equals(new MessageId("msg-a1")))
                        .findFirst()
                        .orElseThrow()
                        .failedAttempts())
                .isEqualTo(1);
    }

    @Test
    void administrationPreservesIdentityOrderingAndCleansInBoundedBatches() {
        append(message("admin-ordered-1", "inventory.commands", "tenant-a:admin-ordered"));
        append(message("admin-ordered-2", "inventory.commands", "tenant-a:admin-ordered"));
        append(message("admin-published-1", "inventory.commands", "tenant-a:published-1"));
        append(message("admin-published-2", "inventory.commands", "tenant-a:published-2"));
        append(message("admin-pending", "inventory.commands", "tenant-a:pending"));
        var batch = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(batch.messages())
                .extracting(entry -> entry.message().descriptor().id().value())
                .containsExactlyInAnyOrder(
                        "admin-ordered-1",
                        "admin-published-1",
                        "admin-published-2",
                        "admin-pending");
        assertThat(outbox.markTerminal(
                        new MessageId("admin-ordered-1"),
                        batch.claimToken(),
                        Instant.now(),
                        "InvalidPayload"))
                .isTrue();
        assertThat(outbox.markPublished(
                        new MessageId("admin-published-1"), batch.claimToken(), Instant.EPOCH))
                .isTrue();
        assertThat(outbox.markPublished(
                        new MessageId("admin-published-2"), batch.claimToken(), Instant.EPOCH))
                .isTrue();

        var terminal = outbox.findTerminal(10).getFirst();
        assertThat(terminal.id()).isEqualTo(new MessageId("admin-ordered-1"));
        assertThat(outbox.redrive(terminal.id(), "stale-token")).isFalse();
        assertThat(outbox.redrive(terminal.id(), terminal.redriveToken())).isTrue();

        var redriven = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(redriven.messages())
                .extracting(entry -> entry.message().descriptor().id().value())
                .containsExactly("admin-ordered-1");
        SerializedMessage redrivenMessage = redriven.messages().getFirst().message();
        assertThat(redrivenMessage.descriptor().type().value())
                .isEqualTo("io.github.ande1922.moduvera.reference.inventory.reserve.v1");
        assertThat(redrivenMessage.descriptor().destination())
                .isEqualTo(new Destination("inventory.commands"));
        assertThat(redrivenMessage.descriptor().partitionKey())
                .isEqualTo("tenant-a:admin-ordered");
        assertThat(redrivenMessage.payload())
                .containsExactly("{\"orderId\":\"42\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(redriven.messages().getFirst().failedAttempts()).isEqualTo(1);
        assertThat(outbox.findTerminal(10)).isEmpty();

        assertThat(outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 1)).isEqualTo(1);
        assertThat(outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 1)).isEqualTo(1);
        assertThat(outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 1)).isZero();
        assertThat(outbox.backlog().pendingCount()).isEqualTo(3);
    }

    @Test
    void cleanupDeletesOnlyOldPublishedRowsInBoundedBatches() {
        append(message("msg-old"));
        append(message("msg-new", "destination-a", "order-new"));
        append(message("msg-pending", "destination-a", "order-pending"));
        var batch = outbox.claim(3, Duration.ofSeconds(30)).orElseThrow();
        for (var entry : batch.messages()) {
            if (entry.message().descriptor().id().value().equals("msg-old")) {
                outbox.markPublished(entry.message().descriptor().id(), batch.claimToken(), Instant.EPOCH);
            } else if (entry.message().descriptor().id().value().equals("msg-new")) {
                outbox.markPublished(entry.message().descriptor().id(), batch.claimToken(), Instant.now());
            }
        }

        assertThat(outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM moduvera_message_outbox WHERE message_id = 'msg-old'",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM moduvera_message_outbox WHERE message_id IN ('msg-new','msg-pending')",
                        Integer.class))
                .isEqualTo(2);
    }

    @Test
    void lifecycleQueriesCanUseTheirDialectSpecificIndexes() {
        for (int index = 0; index < 240; index++) {
            append(message("idx-" + index, "destination-a", "key-" + index));
        }
        jdbc.update(
                "UPDATE moduvera_message_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE message_id LIKE '%0'");
        jdbc.update(
                "UPDATE moduvera_message_outbox SET status = 'TERMINAL', terminal_at = CURRENT_TIMESTAMP WHERE message_id LIKE '%1'");
        jdbc.execute("ANALYZE moduvera_message_outbox");

        assertPlanUses(
                "idx_moduvera_message_outbox_pending",
                "SELECT message_id FROM moduvera_message_outbox WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY next_attempt_at, occurred_at, message_id LIMIT 10");
        assertPlanUses(
                "idx_moduvera_message_outbox_scope_order",
                "SELECT message_id FROM moduvera_message_outbox WHERE status = 'PENDING' AND destination = 'destination-a' AND partition_key = 'key-42' ORDER BY occurred_at, message_id LIMIT 10");
        assertPlanUses(
                "idx_moduvera_message_outbox_published_cleanup",
                "SELECT message_id FROM moduvera_message_outbox WHERE status = 'PUBLISHED' AND published_at < CURRENT_TIMESTAMP ORDER BY published_at, message_id LIMIT 10");
        assertPlanUses(
                "idx_moduvera_message_outbox_terminal_redrive",
                "SELECT message_id FROM moduvera_message_outbox WHERE status = 'TERMINAL' ORDER BY terminal_at, message_id LIMIT 10");
    }

    @Test
    void boundedCleanupAndClaimCanCompleteConcurrently() throws Exception {
        for (int index = 0; index < 20; index++) {
            append(message("published-" + index, "destination-a", "published-key-" + index));
        }
        var published = outbox.claim(20, Duration.ofSeconds(30)).orElseThrow();
        published.messages().forEach(entry -> assertThat(outbox.markPublished(
                        entry.message().descriptor().id(), published.claimToken(), Instant.EPOCH))
                .isTrue());
        for (int index = 0; index < 20; index++) {
            append(message("pending-" + index, "destination-a", "pending-key-" + index));
        }

        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var cleanup = workers.submit(() -> {
                start.await();
                return outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 10);
            });
            var claim = workers.submit(() -> {
                start.await();
                return secondOutbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
            });
            start.countDown();

            assertThat(cleanup.get()).isEqualTo(10);
            assertThat(claim.get().messages()).hasSize(10);
        }
    }

    @Test
    void usesDatabaseUniquenessForConsumerScopedDeduplication() {
        var tenant = new TenantId("tenant-a");
        var id = new MessageId("msg-duplicate");
        assertThat(inbox.tryStart(tenant, "inventory", id, Instant.now())).isTrue();
        assertThat(inbox.tryStart(tenant, "inventory", id, Instant.now())).isFalse();
        assertThat(inbox.tryStart(tenant, "order", id, Instant.now())).isTrue();
    }

    @Test
    void commitsBusinessChangesOncePerTrustedTenantAndConsumerScope() {
        var mapper = new KafkaMessageMapper();
        var tenantA = inboxMessage("msg-inbox-scope", "tenant-a");
        var inventory = inboxConsumer("inventory", recordBusinessChange("inventory"));

        inventory.accept(mapper.toSpringMessage(tenantA));
        inventory.accept(mapper.toSpringMessage(tenantA));
        inboxConsumer("audit", recordBusinessChange("audit"))
                .accept(mapper.toSpringMessage(tenantA));
        inventory.accept(mapper.toSpringMessage(inboxMessage("msg-inbox-scope", "tenant-b")));

        assertThat(businessScopes())
                .containsExactly(
                        "tenant-a:audit:msg-inbox-scope",
                        "tenant-a:inventory:msg-inbox-scope",
                        "tenant-b:inventory:msg-inbox-scope");
        assertThat(inboxRecordCount("msg-inbox-scope")).isEqualTo(3);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void publicConsumerSkipsUnavailablePreparationForARealCommittedInboxReplay() {
        var mapper = new KafkaMessageMapper();
        var serialized = inboxMessage("msg-committed-replay", "tenant-a");
        var messageId = serialized.descriptor().id();
        var template = inboxTemplate("inventory");
        assertThat(handle("tenant-a", template, messageId, () -> {}))
                .isEqualTo(InboxOutcome.APPLIED);

        var preparationAttempts = new AtomicInteger();
        Consumer<byte[]> unavailablePreparation = ignored -> {
            preparationAttempts.incrementAndGet();
            throw new IllegalStateException("preparation unavailable");
        };
        ApplicationMessageHandler<byte[]> handler = (payload, originalMessageId) -> {
            if (template.isProcessed(originalMessageId)) {
                return;
            }
            unavailablePreparation.accept(payload);
            template.handle(originalMessageId, () -> {});
        };
        Consumer<org.springframework.messaging.Message<byte[]>> replay = inboxConsumers.forContract(
                inboxContract(),
                message -> handler.handle(message.payload(), message.descriptor().id()));

        replay.accept(mapper.toSpringMessage(serialized));

        assertThat(preparationAttempts).hasValue(0);
        assertThat(inboxRecordCount(messageId.value())).isEqualTo(1);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void rollsBackInboxAndBusinessChangeTogetherSoTheMessageCanRetry() {
        var mapper = new KafkaMessageMapper();
        var serialized = inboxMessage("msg-inbox-retry", "tenant-a");
        var failing = inboxConsumer("inventory", message -> {
            recordBusinessChange("inventory").accept(message);
            throw new IllegalStateException("handler failed");
        });

        assertThatThrownBy(() -> failing.accept(mapper.toSpringMessage(serialized)))
                .isInstanceOf(NonRetryableMessageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(businessRecordCount("msg-inbox-retry")).isZero();
        assertThat(inboxRecordCount("msg-inbox-retry")).isZero();

        var retry = inboxConsumer("inventory", recordBusinessChange("inventory"));
        retry.accept(mapper.toSpringMessage(serialized));
        assertThat(businessRecordCount("msg-inbox-retry")).isEqualTo(1);
        assertThat(inboxRecordCount("msg-inbox-retry")).isEqualTo(1);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void missingTrustedTenantContextFailsClosedWithoutPersistingInboxState() {
        var template = new InboxTemplate(
                "inventory",
                inbox,
                new SpringTransactionBoundary(transactions),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
        AtomicInteger businessChanges = new AtomicInteger();

        assertThatThrownBy(() -> template.handle(
                        new MessageId("msg-missing-context"), businessChanges::incrementAndGet))
                .isInstanceOf(MissingExecutionContextException.class);

        assertThat(businessChanges).hasValue(0);
        assertThat(inboxRecordCount("msg-missing-context")).isZero();
    }

    @Test
    void precheckReadsOnlyCommittedStateWithinTenantAndConsumerIdentity() {
        var id = new MessageId("msg-precheck-scope");
        var inventory = inboxTemplate("inventory");

        assertThat(isProcessed("tenant-a", inventory, id)).isFalse();
        assertThat(inboxRecordCount(id.value())).isZero();

        assertThat(handle("tenant-a", inventory, id, () -> {}))
                .isEqualTo(InboxOutcome.APPLIED);
        assertThat(isProcessed("tenant-a", inventory, id)).isTrue();
        assertThat(isProcessed("tenant-a", inboxTemplate("audit"), id)).isFalse();
        assertThat(isProcessed("tenant-b", inventory, id)).isFalse();
    }

    @Test
    void precheckCannotSeeUncommittedOrRolledBackInboxRows() throws Exception {
        assertPrecheckVisibility("msg-visibility-commit", false);
        assertPrecheckVisibility("msg-visibility-rollback", true);
    }

    @Test
    void committedFirstWriterWinsAfterBothPrechecksMiss() throws Exception {
        assertConcurrentPrecheckRace("msg-race-commit", false);
    }

    @Test
    void rolledBackFirstWriterLetsTheCompetingCallCommit() throws Exception {
        assertConcurrentPrecheckRace("msg-race-rollback", true);
    }

    @Test
    void domainFailureRollsBackInboxAndBusinessBeforeRetry() {
        var id = new MessageId("msg-domain-failure");
        var template = inboxTemplate("inventory");

        assertThatThrownBy(() -> handle("tenant-a", template, id, () -> {
                    recordTemplateBusinessChange("inventory", id).run();
                    throw new IllegalStateException("domain failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertAtomicInboxState(id, "domain-retry-outbox", 0);

        assertThat(handle("tenant-a", template, id, atomicInboxWork(id, "domain-retry-outbox")))
                .isEqualTo(InboxOutcome.APPLIED);
        assertAtomicInboxState(id, "domain-retry-outbox", 1);
    }

    @Test
    void outboxWriteFailureRollsBackInboxAndBusinessBeforeRetry() {
        var id = new MessageId("msg-outbox-write-failure");
        var template = inboxTemplate("inventory");
        append(message("occupied-outbox-id", "inventory.events", "occupied"));

        assertThatThrownBy(() -> handle("tenant-a", template, id, () -> {
                    recordTemplateBusinessChange("inventory", id).run();
                    publication.append(message("occupied-outbox-id", "inventory.events", "occupied"));
                }))
                .isInstanceOf(RuntimeException.class);
        assertThat(businessRecordCount(id.value())).isZero();
        assertThat(inboxRecordCount(id.value())).isZero();
        assertThat(outboxRecordCount("occupied-outbox-id")).isEqualTo(1);

        assertThat(handle("tenant-a", template, id, atomicInboxWork(id, "outbox-write-retry")))
                .isEqualTo(InboxOutcome.APPLIED);
        assertAtomicInboxState(id, "outbox-write-retry", 1);
    }

    @Test
    void failureAfterOutboxWriteRollsBackAllThreeResourcesBeforeRetry() {
        var id = new MessageId("msg-after-outbox-failure");
        var template = inboxTemplate("inventory");

        assertThatThrownBy(() -> handle("tenant-a", template, id, () -> {
                    atomicInboxWork(id, "after-outbox-event").run();
                    throw new IllegalStateException("after outbox failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertAtomicInboxState(id, "after-outbox-event", 0);

        assertThat(handle("tenant-a", template, id, atomicInboxWork(id, "after-outbox-event")))
                .isEqualTo(InboxOutcome.APPLIED);
        assertAtomicInboxState(id, "after-outbox-event", 1);
    }

    @Test
    void commitFailureDoesNotReturnAppliedAndRollsBackInbox() {
        var id = new MessageId("msg-commit-failure");

        assertThatThrownBy(() -> handle("tenant-a", inboxTemplate("inventory"), id, () -> {
                    jdbc.update("INSERT INTO test_deferred_commit_record(id) VALUES (42)");
                    jdbc.update("INSERT INTO test_deferred_commit_record(id) VALUES (42)");
                }))
                .isInstanceOf(RuntimeException.class);

        assertThat(inboxRecordCount(id.value())).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM test_deferred_commit_record", Integer.class))
                .isZero();
    }

    @Test
    void productionBoundaryRejectsExistingAndHelperOpenedTopLevelTransactions() {
        var outerId = new MessageId("msg-existing-transaction");
        var helperId = new MessageId("msg-helper-transaction");

        assertThatThrownBy(() -> ExecutionContextHolder.run(
                        inboxContext("tenant-a"),
                        () -> transactions.executeWithoutResult(ignored -> inboxTemplate("inventory")
                                .handle(outerId, () -> {}))))
                .isInstanceOf(NestedTransactionBoundaryException.class);
        assertThat(inboxRecordCount(outerId.value())).isZero();

        assertThatThrownBy(() -> handle("tenant-a", inboxTemplate("inventory"), helperId, () -> {
                    atomicInboxWork(helperId, "helper-nested-event").run();
                    openTopLevelTransactionFromHelper();
                }))
                .isInstanceOf(NestedTransactionBoundaryException.class);
        assertAtomicInboxState(helperId, "helper-nested-event", 0);
    }

    private void assertPrecheckVisibility(String messageId, boolean rollBack) throws Exception {
        var id = new MessageId(messageId);
        var template = inboxTemplate("inventory");
        InboxDatabaseConcurrency.assertPrecheckVisibility(
                inboxOperations(template), messageId, rollBack);
    }

    private void assertConcurrentPrecheckRace(String messageId, boolean rollBackFirst)
            throws Exception {
        var id = new MessageId(messageId);
        var template = inboxTemplate("inventory");
        InboxDatabaseConcurrency.assertPrecheckRace(
                inboxOperations(template),
                messageId,
                rollBackFirst,
                this::atomicInboxWork,
                this::inboxInsertIsWaiting);
        assertAtomicInboxState(id, messageId + "-event", 1);
    }

    private boolean inboxInsertIsWaiting() {
        return jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                          FROM pg_stat_activity
                         WHERE pid <> pg_backend_pid()
                           AND datname = current_database()
                           AND wait_event_type = 'Lock'
                           AND query LIKE '%INSERT%moduvera_message_inbox%'
                        """,
                        Integer.class)
                > 0;
    }

    private InboxTemplate inboxTemplate(String consumerId) {
        return new InboxTemplate(
                consumerId,
                inbox,
                inboxTransactions,
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
    }

    private static boolean isProcessed(
            String tenantId, InboxTemplate template, MessageId messageId) {
        return ExecutionContextHolder.call(
                inboxContext(tenantId), () -> template.isProcessed(messageId));
    }

    private static InboxOutcome handle(
            String tenantId, InboxTemplate template, MessageId messageId, Runnable businessChange) {
        return ExecutionContextHolder.call(
                inboxContext(tenantId), () -> template.handle(messageId, businessChange));
    }

    private static InboxDatabaseConcurrency.Operations inboxOperations(InboxTemplate template) {
        return new InboxDatabaseConcurrency.Operations() {
            @Override
            public boolean isProcessed(MessageId messageId) {
                return JdbcMessagingStoreIT.isProcessed("tenant-a", template, messageId);
            }

            @Override
            public InboxOutcome handle(MessageId messageId, Runnable businessChange) {
                return JdbcMessagingStoreIT.handle(
                        "tenant-a", template, messageId, businessChange);
            }
        };
    }

    private Runnable atomicInboxWork(MessageId messageId, String outboxMessageId) {
        return () -> {
            recordTemplateBusinessChange("inventory", messageId).run();
            publication.append(message(outboxMessageId, "inventory.events", messageId.value()));
        };
    }

    private Runnable recordTemplateBusinessChange(String consumerId, MessageId messageId) {
        return () -> {
            var context = ExecutionContextHolder.require();
            jdbc.update(
                    """
                    INSERT INTO test_inbox_business_record(tenant_id, consumer_id, message_id)
                    VALUES (?, ?, ?)
                    """,
                    context.tenantId().value(),
                    consumerId,
                    messageId.value());
        };
    }

    private void assertAtomicInboxState(
            MessageId messageId, String outboxMessageId, int expectedCount) {
        assertThat(businessRecordCount(messageId.value())).isEqualTo(expectedCount);
        assertThat(inboxRecordCount(messageId.value())).isEqualTo(expectedCount);
        assertThat(outboxRecordCount(outboxMessageId)).isEqualTo(expectedCount);
    }

    private int outboxRecordCount(String messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM moduvera_message_outbox WHERE message_id = ?",
                Integer.class,
                messageId);
    }

    private void openTopLevelTransactionFromHelper() {
        inboxTransactions.inTransaction(() -> null);
    }

    private static ExecutionContext inboxContext(String tenantId) {
        return ExecutionContext.initiatedBy(
                new TenantId(tenantId),
                new Actor(ActorType.SERVICE, "inventory-service"),
                "corr-inbox-template");
    }

    private static Set<String> claimIds(
            JdbcOutboxStore store, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return new HashSet<>(ids(store.claim(10, Duration.ofSeconds(30)).orElseThrow()));
    }

    private Consumer<org.springframework.messaging.Message<byte[]>> inboxConsumer(
            String consumerId, Consumer<SerializedMessage> businessChange) {
        var template = inboxTemplate(consumerId);
        return inboxConsumers.forContract(inboxContract(), serialized -> {
            var messageId = serialized.descriptor().id();
            if (template.isProcessed(messageId)) {
                return;
            }
            template.handle(messageId, () -> businessChange.accept(serialized));
        });
    }

    private Consumer<SerializedMessage> recordBusinessChange(String consumerId) {
        return serialized -> {
            var context = ExecutionContextHolder.require();
            assertThat(context.tenantId()).isEqualTo(serialized.descriptor().tenantId());
            assertThat(context.actor().subjectId()).isEqualTo("inventory-service");
            jdbc.update(
                    """
                    INSERT INTO test_inbox_business_record(tenant_id, consumer_id, message_id)
                    VALUES (?, ?, ?)
                    """,
                    context.tenantId().value(),
                    consumerId,
                    serialized.descriptor().id().value());
        };
    }

    private java.util.List<String> businessScopes() {
        return jdbc.queryForList(
                """
                SELECT CONCAT(tenant_id, ':', consumer_id, ':', message_id)
                  FROM test_inbox_business_record
                 ORDER BY tenant_id, consumer_id, message_id
                """,
                String.class);
    }

    private int businessRecordCount(String messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_inbox_business_record WHERE message_id = ?",
                Integer.class,
                messageId);
    }

    private int inboxRecordCount(String messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM moduvera_message_inbox WHERE message_id = ?",
                Integer.class,
                messageId);
    }

    private void append(SerializedMessage message) {
        transactions.executeWithoutResult(ignored -> publication.append(message));
    }

    private OutboxWorker worker(MessageTransport transport, int maxAttempts) {
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

    private void expireClaim(String messageId) {
        jdbc.update(
                "UPDATE moduvera_message_outbox SET claim_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE message_id = ?",
                messageId);
    }

    private DataSource postgresDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static TransactionTemplate transactionTemplate(DataSource source) {
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private static java.util.List<String> ids(io.github.ande1922.moduvera.message.outbox.ClaimedOutboxBatch batch) {
        return batch.messages().stream()
                .map(entry -> entry.message().descriptor().id().value())
                .toList();
    }

    private void assertPlanUses(String indexName, String query) {
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            assertThat(String.join("\n", jdbc.queryForList("EXPLAIN " + query, String.class)))
                    .contains(indexName);
        });
    }

    private SerializedMessage message(String id) {
        return message(id, "inventory.commands", "tenant-a:order-42");
    }

    private SerializedMessage message(String id, String partitionKey) {
        return message(id, "inventory.commands", "tenant-a:" + partitionKey);
    }

    private SerializedMessage message(String id, String destination, String partitionKey) {
        var descriptor = new MessageDescriptor(
                new MessageId(id),
                MessageKind.ASYNC_COMMAND,
                new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                URI.create("urn:service:order"),
                new Destination(destination),
                Instant.parse("2026-08-30T00:00:00Z"),
                new TenantId("tenant-a"),
                new Actor(ActorType.SERVICE, "order-service"),
                "corr-1",
                new MessageId("request-1"),
                new Initiator(ActorType.USER, "alice"),
                partitionKey);
        return new SerializedMessage(
                descriptor,
                SerializedMessage.JSON,
                "{\"orderId\":\"42\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static InboundMessageContract inboxContract() {
        return new InboundMessageContract(
                MessageKind.ASYNC_COMMAND,
                new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                URI.create("urn:service:order"),
                new Destination("inventory.commands"),
                new Actor(ActorType.SERVICE, "inventory-service", Set.of("inventory:reserve")));
    }

    private static SerializedMessage inboxMessage(String id, String tenantId) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        MessageKind.ASYNC_COMMAND,
                        new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                        URI.create("urn:service:order"),
                        new Destination("inventory.commands"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId(tenantId),
                        new Actor(ActorType.SERVICE, "order-service"),
                        "corr-inbox",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        tenantId + ":order-42"),
                "{}");
    }
}
