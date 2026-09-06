package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.NestedTransactionBoundaryException;
import io.github.ande1922.moduvera.data.spring.SpringTransactionBoundary;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.inbox.InboxOutcome;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcMessagingMySqlIT {

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer(System.getProperty("mysql.test.image", "mysql:8.4.11"));

    private JdbcTemplate jdbc;
    private JdbcTemplate databaseObserver;
    private JdbcOutboxStore outbox;
    private JdbcOutboxStore secondOutbox;
    private JdbcDurablePublication publication;
    private JdbcInboxRepository inbox;
    private SpringTransactionBoundary inboxTransactions;
    private TransactionTemplate transactions;

    @BeforeAll
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        databaseObserver = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        new DatabaseMigrator(dataSource)
                .migrate(new MigrationPlan(
                        new DatabaseComponent("messaging"),
                        List.of("classpath:db/moduvera-messaging/mysql"),
                        true));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE test_business_record (id VARCHAR(128) PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE test_inbox_business_record (
                    tenant_id VARCHAR(64) NOT NULL,
                    consumer_id VARCHAR(128) NOT NULL,
                    message_id VARCHAR(128) NOT NULL,
                    PRIMARY KEY (tenant_id, consumer_id, message_id)
                )
                """);
        var named = new NamedParameterJdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        inboxTransactions = new SpringTransactionBoundary(transactions);
        outbox = new JdbcOutboxStore(
                named,
                JdbcMessagingDialect.MYSQL,
                transactions,
                new LocalOutboxWakeSignal());
        publication = new JdbcDurablePublication(dataSource, outbox);
        secondOutbox = new JdbcOutboxStore(
                named,
                JdbcMessagingDialect.MYSQL,
                transactions,
                new LocalOutboxWakeSignal());
        inbox = new JdbcInboxRepository(named, JdbcMessagingDialect.MYSQL);
    }

    @BeforeEach
    void clearTables() {
        jdbc.execute("DELETE FROM moduvera_message_inbox");
        jdbc.execute("DELETE FROM moduvera_message_outbox");
        jdbc.execute("DELETE FROM test_business_record");
        jdbc.execute("DELETE FROM test_inbox_business_record");
    }

    @Test
    void twoStoresAtomicallyClaimWithDatabaseLeasesAndTokenFencing() throws Exception {
        for (int index = 0; index < 12; index++) {
            append(message("mysql-msg-" + index, "destination-a", "key-" + index));
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
            assertThat(all).hasSize(12);
        }

        jdbc.update(
                "UPDATE moduvera_message_outbox SET claim_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND");
        var reclaimed = outbox.claim(12, Duration.ofSeconds(30)).orElseThrow();
        assertThat(reclaimed.messages()).allMatch(entry -> entry.failedAttempts() == 0);
    }

    @Test
    void rejectsEveryStateUpdateFromAnExpiredClaimToken() {
        append(message("mysql-lease", "destination-a", "lease-key"));
        var abandoned = outbox.claim(1, Duration.ofSeconds(30)).orElseThrow();
        jdbc.update(
                "UPDATE moduvera_message_outbox SET claim_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE message_id = 'mysql-lease'");

        var reclaimed = secondOutbox.claim(1, Duration.ofSeconds(30)).orElseThrow();

        assertThat(reclaimed.claimToken()).isNotEqualTo(abandoned.claimToken());
        assertThat(reclaimed.messages().getFirst().failedAttempts()).isZero();
        assertThat(outbox.markPublished(
                        new MessageId("mysql-lease"), abandoned.claimToken(), Instant.now()))
                .isFalse();
        assertThat(outbox.markFailed(
                        new MessageId("mysql-lease"),
                        abandoned.claimToken(),
                        Duration.ZERO,
                        "Timeout"))
                .isFalse();
        assertThat(outbox.markTerminal(
                        new MessageId("mysql-lease"),
                        abandoned.claimToken(),
                        Instant.now(),
                        "Poison"))
                .isFalse();
        assertThat(secondOutbox.markPublished(
                        new MessageId("mysql-lease"), reclaimed.claimToken(), Instant.now()))
                .isTrue();
    }

    @Test
    void qualifiesOrderingFailureTerminalRedriveCleanupAndDedupe() {
        append(message("mysql-a1", "destination-a", "shared-key"));
        append(message("mysql-a2", "destination-a", "shared-key"));
        append(message("mysql-b1", "destination-b", "shared-key"));
        var first = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(ids(first)).containsExactlyInAnyOrder("mysql-a1", "mysql-b1");
        var a1 = first.messages().stream()
                .filter(entry -> entry.message().descriptor().id().value().equals("mysql-a1"))
                .findFirst()
                .orElseThrow();
        assertThat(outbox.markFailed(a1.message().descriptor().id(), first.claimToken(), Duration.ZERO, "Timeout"))
                .isTrue();
        var b1 = first.messages().stream()
                .filter(entry -> entry.message().descriptor().id().value().equals("mysql-b1"))
                .findFirst()
                .orElseThrow();
        assertThat(outbox.markTerminal(
                        b1.message().descriptor().id(), first.claimToken(), Instant.now(), "InvalidSchema"))
                .isTrue();

        var retry = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(ids(retry)).containsExactly("mysql-a1");
        assertThat(retry.messages().getFirst().failedAttempts()).isEqualTo(1);
        assertThat(outbox.markPublished(
                        retry.messages().getFirst().message().descriptor().id(),
                        retry.claimToken(),
                        Instant.EPOCH))
                .isTrue();
        var a2 = outbox.claim(10, Duration.ofSeconds(30)).orElseThrow();
        assertThat(ids(a2)).containsExactly("mysql-a2");

        var terminal = outbox.findTerminal(10).getFirst();
        assertThat(outbox.redrive(terminal.id(), "stale")).isFalse();
        assertThat(outbox.redrive(terminal.id(), terminal.redriveToken())).isTrue();
        assertThat(outbox.deletePublishedBefore(Instant.now().minusSeconds(1), 10)).isEqualTo(1);

        var tenant = new TenantId("tenant-a");
        var id = new MessageId("mysql-dedupe");
        assertThat(inbox.tryStart(tenant, "inventory", id, Instant.now())).isTrue();
        assertThat(inbox.tryStart(tenant, "inventory", id, Instant.now())).isFalse();
    }

    @Test
    void keepsBusinessOutboxAndInboxChangesInTheSameTransaction() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
                    jdbc.update("INSERT INTO test_business_record(id) VALUES (?)", "rollback");
                    append(message("mysql-rollback", "destination-a", "rollback-key"));
                    inbox.tryStart(
                            new TenantId("tenant-a"),
                            "inventory",
                            new MessageId("inbox-rollback"),
                            Instant.now());
                    throw new IllegalStateException("rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_business_record", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moduvera_message_outbox", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moduvera_message_inbox", Integer.class))
                .isZero();
    }

    @Test
    void inboxTemplateCommitsOncePerTenantAndConsumerScope() {
        var id = new MessageId("mysql-inbox-scope");
        var inventory = inboxTemplate("inventory");

        assertThat(isProcessed("tenant-a", inventory, id)).isFalse();
        assertThat(inboxRecordCount(id)).isZero();
        assertThat(handle("tenant-a", inventory, id, recordBusinessChange("inventory", id)))
                .isEqualTo(InboxOutcome.APPLIED);
        assertThat(isProcessed("tenant-a", inventory, id)).isTrue();
        assertThat(isProcessed("tenant-a", inboxTemplate("audit"), id)).isFalse();
        assertThat(isProcessed("tenant-b", inventory, id)).isFalse();
        assertThat(handle("tenant-a", inventory, id, recordBusinessChange("inventory", id)))
                .isEqualTo(InboxOutcome.DUPLICATE);
        assertThat(handle(
                        "tenant-a",
                        inboxTemplate("audit"),
                        id,
                        recordBusinessChange("audit", id)))
                .isEqualTo(InboxOutcome.APPLIED);
        assertThat(handle("tenant-b", inventory, id, recordBusinessChange("inventory", id)))
                .isEqualTo(InboxOutcome.APPLIED);

        assertThat(businessScopes())
                .containsExactly(
                        "tenant-a:audit:mysql-inbox-scope",
                        "tenant-a:inventory:mysql-inbox-scope",
                        "tenant-b:inventory:mysql-inbox-scope");
        assertThat(inboxRecordCount(id)).isEqualTo(3);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void precheckCannotSeeUncommittedOrRolledBackInboxRows() throws Exception {
        assertPrecheckVisibility("mysql-visibility-commit", false);
        assertPrecheckVisibility("mysql-visibility-rollback", true);
    }

    @Test
    void committedFirstWriterWinsAfterBothMySqlPrechecksMiss() throws Exception {
        assertConcurrentPrecheckRace("mysql-race-commit", false);
    }

    @Test
    void rolledBackFirstWriterLetsTheCompetingMySqlCallCommit() throws Exception {
        assertConcurrentPrecheckRace("mysql-race-rollback", true);
    }

    @Test
    void inboxAndBusinessMutationRollBackTogetherAndCanRetry() {
        var id = new MessageId("mysql-inbox-retry");
        var template = inboxTemplate("inventory");

        assertThatThrownBy(() -> handle("tenant-a", template, id, () -> {
                    recordBusinessChange("inventory", id).run();
                    throw new IllegalStateException("handler failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(businessRecordCount(id)).isZero();
        assertThat(inboxRecordCount(id)).isZero();

        assertThat(handle("tenant-a", template, id, recordBusinessChange("inventory", id)))
                .isEqualTo(InboxOutcome.APPLIED);
        assertThat(businessRecordCount(id)).isEqualTo(1);
        assertThat(inboxRecordCount(id)).isEqualTo(1);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void postOutboxFailureAndNestedHelperRollBackTheMySqlInboxTransaction() {
        var failureId = new MessageId("mysql-after-outbox-failure");
        var nestedId = new MessageId("mysql-helper-nested");

        assertThatThrownBy(() -> handle("tenant-a", inboxTemplate("inventory"), failureId, () -> {
                    atomicInboxWork(failureId, "mysql-after-outbox-event").run();
                    throw new IllegalStateException("after outbox failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertAtomicInboxState(failureId, "mysql-after-outbox-event", 0);

        assertThatThrownBy(() -> handle("tenant-a", inboxTemplate("inventory"), nestedId, () -> {
                    atomicInboxWork(nestedId, "mysql-helper-event").run();
                    openTopLevelTransactionFromHelper();
                }))
                .isInstanceOf(NestedTransactionBoundaryException.class);
        assertAtomicInboxState(nestedId, "mysql-helper-event", 0);
    }

    @Test
    void lifecycleQueriesExposeTheirDialectSpecificIndexesToThePlanner() {
        for (int index = 0; index < 240; index++) {
            append(message("mysql-idx-" + index, "destination-a", "key-" + index));
        }
        jdbc.update(
                "UPDATE moduvera_message_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 DAY WHERE message_id LIKE '%0'");
        jdbc.update(
                "UPDATE moduvera_message_outbox SET status = 'TERMINAL', terminal_at = CURRENT_TIMESTAMP(6) WHERE message_id LIKE '%1'");
        jdbc.execute("ANALYZE TABLE moduvera_message_outbox");

        assertPossibleIndex(
                "idx_moduvera_message_outbox_pending",
                "SELECT message_id FROM moduvera_message_outbox WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6) ORDER BY next_attempt_at, occurred_at, message_id LIMIT 10");
        assertPossibleIndex(
                "idx_moduvera_message_outbox_scope_order",
                "SELECT message_id FROM moduvera_message_outbox WHERE status = 'PENDING' AND destination = 'destination-a' AND partition_key = 'key-42' ORDER BY occurred_at, message_id LIMIT 10");
        assertPossibleIndex(
                "idx_moduvera_message_outbox_published_cleanup",
                "SELECT message_id FROM moduvera_message_outbox WHERE status = 'PUBLISHED' AND published_at < CURRENT_TIMESTAMP(6) ORDER BY published_at, message_id LIMIT 10");
        assertPossibleIndex(
                "idx_moduvera_message_outbox_terminal_redrive",
                "SELECT message_id FROM moduvera_message_outbox WHERE status = 'TERMINAL' ORDER BY terminal_at, message_id LIMIT 10");
    }

    @Test
    void boundedCleanupAndClaimCanCompleteConcurrently() throws Exception {
        for (int index = 0; index < 20; index++) {
            append(message(
                    "mysql-published-" + index, "destination-a", "published-key-" + index));
        }
        var published = outbox.claim(20, Duration.ofSeconds(30)).orElseThrow();
        published.messages().forEach(entry -> assertThat(outbox.markPublished(
                        entry.message().descriptor().id(), published.claimToken(), Instant.EPOCH))
                .isTrue());
        for (int index = 0; index < 20; index++) {
            append(message(
                    "mysql-pending-" + index, "destination-a", "pending-key-" + index));
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

    private void assertPrecheckVisibility(String messageId, boolean rollBack) throws Exception {
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
        return databaseObserver.queryForObject(
                        """
                        SELECT COUNT(*)
                          FROM performance_schema.data_lock_waits waits
                          JOIN performance_schema.data_locks requested
                            ON requested.ENGINE = waits.ENGINE
                           AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                         WHERE requested.OBJECT_SCHEMA = DATABASE()
                           AND requested.OBJECT_NAME = 'moduvera_message_inbox'
                        """,
                        Integer.class)
                > 0;
    }

    private static boolean isProcessed(
            String tenantId, InboxTemplate template, MessageId messageId) {
        return ExecutionContextHolder.call(
                context(tenantId), () -> template.isProcessed(messageId));
    }

    private static InboxDatabaseConcurrency.Operations inboxOperations(InboxTemplate template) {
        return new InboxDatabaseConcurrency.Operations() {
            @Override
            public boolean isProcessed(MessageId messageId) {
                return JdbcMessagingMySqlIT.isProcessed("tenant-a", template, messageId);
            }

            @Override
            public InboxOutcome handle(MessageId messageId, Runnable businessChange) {
                return JdbcMessagingMySqlIT.handle(
                        "tenant-a", template, messageId, businessChange);
            }
        };
    }

    private Runnable atomicInboxWork(MessageId messageId, String outboxMessageId) {
        return () -> {
            recordBusinessChange("inventory", messageId).run();
            publication.append(message(outboxMessageId, "inventory.events", messageId.value()));
        };
    }

    private void assertAtomicInboxState(
            MessageId messageId, String outboxMessageId, int expectedCount) {
        assertThat(businessRecordCount(messageId)).isEqualTo(expectedCount);
        assertThat(inboxRecordCount(messageId)).isEqualTo(expectedCount);
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

    private static Set<String> claimIds(
            JdbcOutboxStore store, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return new HashSet<>(ids(store.claim(6, Duration.ofSeconds(30)).orElseThrow()));
    }

    private InboxTemplate inboxTemplate(String consumerId) {
        return new InboxTemplate(
                consumerId,
                inbox,
                inboxTransactions,
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
    }

    private static InboxOutcome handle(
            String tenantId, InboxTemplate template, MessageId messageId, Runnable businessChange) {
        return ExecutionContextHolder.call(
                context(tenantId), () -> template.handle(messageId, businessChange));
    }

    private Runnable recordBusinessChange(String consumerId, MessageId messageId) {
        return () -> {
            var context = ExecutionContextHolder.require();
            assertThat(context.actor().subjectId()).isEqualTo("inventory-service");
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

    private java.util.List<String> businessScopes() {
        return jdbc.queryForList(
                """
                SELECT CONCAT(tenant_id, ':', consumer_id, ':', message_id)
                  FROM test_inbox_business_record
                 ORDER BY tenant_id, consumer_id, message_id
                """,
                String.class);
    }

    private int businessRecordCount(MessageId messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_inbox_business_record WHERE message_id = ?",
                Integer.class,
                messageId.value());
    }

    private int inboxRecordCount(MessageId messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM moduvera_message_inbox WHERE message_id = ?",
                Integer.class,
                messageId.value());
    }

    private static ExecutionContext context(String tenantId) {
        return ExecutionContext.initiatedBy(
                new TenantId(tenantId),
                new Actor(ActorType.SERVICE, "inventory-service"),
                "corr-mysql-inbox");
    }

    private void append(SerializedMessage message) {
        transactions.executeWithoutResult(ignored -> publication.append(message));
    }

    private static java.util.List<String> ids(io.github.ande1922.moduvera.message.outbox.ClaimedOutboxBatch batch) {
        return batch.messages().stream()
                .map(entry -> entry.message().descriptor().id().value())
                .toList();
    }

    private void assertPossibleIndex(String indexName, String query) {
        assertThat(jdbc.queryForList("EXPLAIN " + query))
                .anySatisfy(row -> assertThat(String.valueOf(row.get("possible_keys")))
                        .contains(indexName));
    }

    private static SerializedMessage message(String id, String destination, String partitionKey) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        MessageKind.ASYNC_COMMAND,
                        new MessageType("io.github.ande1922.moduvera.reference.inventory.reserve.v1"),
                        URI.create("urn:service:order"),
                        new Destination(destination),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "order-service"),
                        "corr-mysql",
                        null,
                        new Initiator(ActorType.USER, "alice"),
                        partitionKey),
                "{}");
    }
}
