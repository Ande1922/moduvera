package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxBatch;
import io.github.ande1922.moduvera.message.outbox.MessageTransport;
import io.github.ande1922.moduvera.message.outbox.OutboxWorker;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
class OutboxRelayIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    private JdbcTemplate jdbc;
    private JdbcOutboxStore outbox;
    private JdbcOutboxStore takeoverOutbox;
    private JdbcDurablePublication publication;
    private LocalOutboxWakeSignal wakeSignal;
    private TransactionTemplate transactions;

    @BeforeAll
    void setUpDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new DatabaseMigrator(dataSource)
                .migrate(new MigrationPlan(
                        new DatabaseComponent("messaging"),
                        List.of("classpath:db/moduvera-messaging/postgresql"),
                        true));
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var named = new NamedParameterJdbcTemplate(dataSource);
        wakeSignal = new LocalOutboxWakeSignal();
        outbox = new JdbcOutboxStore(
                named, JdbcMessagingDialect.POSTGRESQL, transactions, wakeSignal);
        takeoverOutbox = new JdbcOutboxStore(
                named,
                JdbcMessagingDialect.POSTGRESQL,
                transactions,
                new LocalOutboxWakeSignal());
        publication = new JdbcDurablePublication(dataSource, outbox);
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.execute("TRUNCATE TABLE moduvera_message_outbox");
    }

    @Test
    void oneWakeContinuouslyDrainsMultipleJdbcBatches() throws Exception {
        append(message("msg-1", "order-1"));
        append(message("msg-2", "order-2"));
        append(message("msg-3", "order-3"));
        List<String> sent = new CopyOnWriteArrayList<>();
        var relay = relay(
                message -> sent.add(message.descriptor().id().value()), properties());

        relay.start();
        try {
            await(() -> sent.size() == 3 && relay.state() == OutboxRelay.State.WAITING);

            assertThat(sent).containsExactlyInAnyOrder("msg-1", "msg-2", "msg-3");
            assertThat(outbox.backlog().pendingCount()).isZero();
            assertThat(outbox.claim(1, Duration.ofSeconds(1))).isEmpty();
        } finally {
            relay.stop();
        }
    }

    @Test
    void relaysPersistedTenantMessageAfterTheOriginatingContextHasClosed() throws Exception {
        SerializedMessage persisted = message("msg-after-request", "order-1");
        var requestContext = ExecutionContext.initiatedBy(
                new TenantId("tenant-a"),
                new Actor(ActorType.USER, "alice"),
                "corr-originating-request");
        ExecutionContextHolder.run(requestContext, () -> append(persisted));
        assertThat(ExecutionContextHolder.current()).isEmpty();

        var sent = new AtomicReference<SerializedMessage>();
        var relay = relay(message -> {
            assertThat(ExecutionContextHolder.current()).isEmpty();
            sent.set(message);
        }, properties());

        relay.start();
        try {
            await(() -> sent.get() != null && relay.state() == OutboxRelay.State.WAITING);
        } finally {
            relay.stop();
        }

        assertThat(sent.get()).usingRecursiveComparison().isEqualTo(persisted);
        assertThat(outbox.backlog().pendingCount()).isZero();
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void concurrentWakeAndPollSignalsNeverOverlapJdbcWorkerExecution() throws Exception {
        for (int index = 0; index < 20; index++) {
            append(message("msg-" + index, "order-" + index));
        }
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        var relay = relay(
                ignored -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    try {
                        if (sends.getAndIncrement() == 0) {
                            firstSendStarted.countDown();
                            awaitLatch(releaseFirstSend);
                        }
                    } finally {
                        active.decrementAndGet();
                    }
                },
                properties());

        relay.start();
        try {
            assertThat(firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            try (var callers = Executors.newFixedThreadPool(8)) {
                List<java.util.concurrent.Future<?>> signals = new ArrayList<>();
                for (int index = 0; index < 40; index++) {
                    signals.add(callers.submit(index % 2 == 0 ? relay::wake : relay::poll));
                }
                for (var signal : signals) {
                    signal.get(5, TimeUnit.SECONDS);
                }
                releaseFirstSend.countDown();
                await(() -> sends.get() == 20 && relay.state() == OutboxRelay.State.WAITING);

                assertThat(maximumActive).hasValue(1);
                assertThat(outbox.backlog().pendingCount()).isZero();
            }
        } finally {
            releaseFirstSend.countDown();
            relay.stop();
        }
    }

    @Test
    void gracefulStopFinishesStartedSendWithoutClaimingNextJdbcRecord() throws Exception {
        append(message("msg-started", "order-1"));
        append(message("msg-unclaimed", "order-2"));
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        var relay = relay(
                message -> {
                    sent.add(message.descriptor().id().value());
                    sendStarted.countDown();
                    awaitLatch(releaseSend);
                },
                properties());

        relay.start();
        try {
            assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            try (var stopper = Executors.newSingleThreadExecutor()) {
                var stopped = stopper.submit((Runnable) relay::stop);
                await(() -> relay.state() == OutboxRelay.State.STOPPING);
                relay.wake();
                relay.poll();
                releaseSend.countDown();
                stopped.get(5, TimeUnit.SECONDS);
            }
        } finally {
            releaseSend.countDown();
            relay.stop();
        }

        assertThat(sent).containsExactly("msg-started");
        ClaimedOutboxBatch remaining =
                takeoverOutbox.claim(10, Duration.ofSeconds(1)).orElseThrow();
        assertThat(ids(remaining)).containsExactly("msg-unclaimed");
        assertThat(outbox.backlog().pendingCount()).isEqualTo(1);
    }

    @Test
    void forcedStopLeavesUnknownJdbcClaimAvailableForLeaseTakeover() throws Exception {
        append(message("msg-interrupted", "order-1"));
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        var forcedStopProperties = properties();
        forcedStopProperties.setClaimLease(Duration.ofMillis(200));
        forcedStopProperties.setShutdownGrace(Duration.ofMillis(50));
        var relay = relay(
                ignored -> {
                    sendStarted.countDown();
                    awaitLatch(neverReleased);
                },
                forcedStopProperties);

        relay.start();
        try {
            assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            relay.stop();
        } finally {
            relay.stop();
        }

        assertThat(outbox.findTerminal(10)).isEmpty();
        assertThat(outbox.backlog().pendingCount()).isEqualTo(1);
        AtomicReference<ClaimedOutboxBatch> reclaimed = new AtomicReference<>();
        await(() -> takeoverOutbox
                .claim(1, Duration.ofSeconds(1))
                .map(batch -> {
                    reclaimed.set(batch);
                    return true;
                })
                .orElse(false));
        assertThat(ids(reclaimed.get())).containsExactly("msg-interrupted");
        assertThat(reclaimed.get().messages().getFirst().failedAttempts()).isZero();
    }

    @Test
    void scriptedTransportPersistsSuccessRetryableAndNonRetryableOutcomes() throws Exception {
        append(message("msg-success", "success"));
        append(message("msg-retry", "retry"));
        append(message("msg-terminal", "terminal"));
        AtomicInteger retrySends = new AtomicInteger();
        AtomicInteger totalSends = new AtomicInteger();
        var relay = relay(
                sent -> {
                    totalSends.incrementAndGet();
                    String id = sent.descriptor().id().value();
                    if (id.equals("msg-retry") && retrySends.incrementAndGet() == 1) {
                        throw new IllegalStateException("retryable acknowledgement failure");
                    }
                    if (id.equals("msg-terminal")) {
                        throw new NonRetryableMessageException("rejected acknowledgement");
                    }
                },
                properties());

        relay.start();
        try {
            await(() -> totalSends.get() == 4 && relay.state() == OutboxRelay.State.WAITING);

            assertThat(retrySends).hasValue(2);
            assertThat(outbox.backlog().pendingCount()).isZero();
            assertThat(outbox.backlog().terminalCount()).isEqualTo(1);
            assertThat(outbox.findTerminal(10))
                    .singleElement()
                    .satisfies(terminal -> {
                        assertThat(terminal.id()).isEqualTo(new MessageId("msg-terminal"));
                        assertThat(terminal.safeFailure())
                                .isEqualTo("NonRetryableMessageException");
                    });
        } finally {
            relay.stop();
        }
    }

    private OutboxRelay relay(
            MessageTransport transport, ModuveraMessagingKafkaProperties properties) {
        var worker = new OutboxWorker(
                outbox,
                transport,
                Clock.systemUTC(),
                System::nanoTime,
                properties.getClaimLease(),
                properties.getLeaseSafetyMargin(),
                properties.getFailureBackoff(),
                properties.getRelayMaxAttempts(),
                PublicationObserver.noop());
        return new OutboxRelay(worker, properties, wakeSignal);
    }

    private void append(SerializedMessage message) {
        transactions.executeWithoutResult(ignored -> publication.append(message));
    }

    private static ModuveraMessagingKafkaProperties properties() {
        var properties = new ModuveraMessagingKafkaProperties();
        properties.setRelayBatchSize(1);
        properties.setRelayPollInterval(Duration.ofHours(1));
        properties.setClaimLease(Duration.ofSeconds(2));
        properties.setLeaseSafetyMargin(Duration.ofMillis(10));
        properties.setBrokerAckTimeout(Duration.ofMillis(10));
        properties.setDatabaseStateUpdateBudget(Duration.ofMillis(10));
        properties.setRelayRunBudget(Duration.ofSeconds(1));
        properties.setShutdownGrace(Duration.ofSeconds(2));
        properties.setFailureBackoff(Duration.ZERO);
        properties.setRelayMaxAttempts(3);
        return properties;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean satisfied = condition.getAsBoolean();
        while (!satisfied && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(5));
            satisfied = condition.getAsBoolean();
        }
        assertThat(satisfied).isTrue();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static List<String> ids(ClaimedOutboxBatch batch) {
        return batch.messages().stream()
                .map(entry -> entry.message().descriptor().id().value())
                .toList();
    }

    private static SerializedMessage message(String id, String partitionKey) {
        return SerializedMessage.json(
                new MessageDescriptor(
                        new MessageId(id),
                        MessageKind.EVENT,
                        new MessageType("io.github.ande1922.moduvera.test.event.v1"),
                        URI.create("urn:service:test"),
                        new Destination("test.events"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        new TenantId("tenant-a"),
                        new Actor(ActorType.SERVICE, "test-service"),
                        "corr-1",
                        null,
                        new Initiator(ActorType.SYSTEM, "test"),
                        "tenant-a:" + partitionKey),
                "{}");
    }
}
