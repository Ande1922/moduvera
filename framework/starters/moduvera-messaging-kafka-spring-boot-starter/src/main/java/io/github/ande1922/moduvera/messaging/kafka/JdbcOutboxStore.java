package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageDescriptor;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.TraceContextCarrier;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxBatch;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxMessage;
import io.github.ande1922.moduvera.message.outbox.OutboxAdministration;
import io.github.ande1922.moduvera.message.outbox.OutboxBacklog;
import io.github.ande1922.moduvera.message.outbox.OutboxStore;
import io.github.ande1922.moduvera.message.outbox.OutboxWakeSignal;
import io.github.ande1922.moduvera.message.outbox.TerminalOutboxMessage;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcOutboxStore implements OutboxStore, OutboxAdministration {

    private static final String PENDING = "PENDING";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String TERMINAL = "TERMINAL";
    private static final String POSTGRESQL_ELIGIBILITY = """
            candidate.status = :pending
              AND candidate.next_attempt_at <= CURRENT_TIMESTAMP
              AND (candidate.claim_expires_at IS NULL
                   OR candidate.claim_expires_at <= CURRENT_TIMESTAMP)
              AND NOT EXISTS (
                  SELECT 1
                    FROM moduvera_message_outbox earlier
                   WHERE earlier.destination = candidate.destination
                     AND earlier.partition_key = candidate.partition_key
                     AND earlier.status = :pending
                     AND (earlier.occurred_at, earlier.message_id)
                         < (candidate.occurred_at, candidate.message_id)
              )
            """;
    private static final String MYSQL_ELIGIBILITY = """
            candidate.status = :pending
              AND candidate.next_attempt_at <= CURRENT_TIMESTAMP(6)
              AND (candidate.claim_expires_at IS NULL
                   OR candidate.claim_expires_at <= CURRENT_TIMESTAMP(6))
              AND NOT EXISTS (
                  SELECT 1
                    FROM moduvera_message_outbox earlier
                   WHERE earlier.destination = candidate.destination
                     AND earlier.partition_key = candidate.partition_key
                     AND earlier.status = :pending
                     AND (earlier.occurred_at < candidate.occurred_at
                          OR (earlier.occurred_at = candidate.occurred_at
                              AND earlier.message_id < candidate.message_id))
              )
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final JdbcMessagingDialect dialect;
    private final TransactionOperations transactions;
    private final OutboxWakeSignal wakeSignal;

    public JdbcOutboxStore(
            NamedParameterJdbcOperations jdbc,
            JdbcMessagingDialect dialect,
            TransactionOperations transactions,
            OutboxWakeSignal wakeSignal) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.dialect = java.util.Objects.requireNonNull(dialect, "dialect");
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.wakeSignal = java.util.Objects.requireNonNull(wakeSignal, "wakeSignal");
    }

    void appendIntent(SerializedMessage message) {
        MessageDescriptor descriptor = message.descriptor();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("messageId", descriptor.id().value());
        parameters.put("messageKind", descriptor.kind().name());
        parameters.put("messageType", descriptor.type().value());
        parameters.put("source", descriptor.source().toString());
        parameters.put("destination", descriptor.destination().value());
        parameters.put("occurredAt", Timestamp.from(descriptor.time()));
        parameters.put("tenantId", descriptor.tenantId().value());
        parameters.put("actorType", descriptor.actor().type().name());
        parameters.put("actorSubject", descriptor.actor().subjectId());
        parameters.put("correlationId", descriptor.correlationId());
        parameters.put(
                "causationId",
                descriptor.causationId() == null ? null : descriptor.causationId().value());
        parameters.put("initiatorType", descriptor.initiator().type().name());
        parameters.put("initiatorSubject", descriptor.initiator().subjectId());
        parameters.put("partitionKey", descriptor.partitionKey());
        parameters.put("contentType", message.contentType());
        parameters.put("payload", message.payload());
        TraceContextCarrier creation = descriptor.creationContext();
        parameters.put("creationTraceParent", creation == null ? null : creation.traceParent());
        parameters.put("creationTraceState", creation == null ? null : creation.traceState());
        parameters.put("status", PENDING);
        jdbc.update(
                ("""
                INSERT INTO moduvera_message_outbox (
                    message_id, message_kind, message_type, source, destination, occurred_at,
                    tenant_id, actor_type, actor_subject, correlation_id,
                    causation_id, initiator_type, initiator_subject, partition_key,
                    content_type, payload, status, next_attempt_at, attempt_count,
                    creation_traceparent, creation_tracestate,
                    publication_traceparent, publication_tracestate, publication_generation)
                VALUES (
                    :messageId, :messageKind, :messageType, :source, :destination, :occurredAt,
                    :tenantId, :actorType, :actorSubject, :correlationId,
                    :causationId, :initiatorType, :initiatorSubject, :partitionKey,
                    :contentType, :payload, :status, %s, 0,
                    :creationTraceParent, :creationTraceState,
                    :creationTraceParent, :creationTraceState, 0)
                """)
                        .formatted(databaseCurrentTimestamp()),
                parameters);
        signalWhenVisible();
    }

    private String databaseCurrentTimestamp() {
        return dialect == JdbcMessagingDialect.MYSQL
                ? "CURRENT_TIMESTAMP(6)"
                : "CURRENT_TIMESTAMP";
    }

    @Override
    public Optional<ClaimedOutboxBatch> claim(int limit, Duration lease) {
        requireClaimArguments(limit, lease);
        String claimToken = UUID.randomUUID().toString();
        List<ClaimedOutboxMessage> messages = dialect == JdbcMessagingDialect.MYSQL
                ? claimMySql(limit, lease, claimToken)
                : claimPostgresql(limit, lease, claimToken);
        return messages.isEmpty()
                ? Optional.empty()
                : Optional.of(new ClaimedOutboxBatch(claimToken, messages));
    }

    @Override
    public Optional<ClaimedOutboxMessage> preparePublication(
            ClaimedOutboxMessage claimed, String claimToken) {
        java.util.Objects.requireNonNull(claimed, "claimed");
        if (claimToken == null || claimToken.isBlank()) {
            return Optional.empty();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox preparation requires its own committed transaction");
        }
        try (var preparation = new OutboxPublicationPreparation()) {
            try {
                var result = transactions.execute(status -> {
                    if (!status.isNewTransaction()
                            || !TransactionSynchronizationManager.isActualTransactionActive()
                            || !TransactionSynchronizationManager.isSynchronizationActive()
                            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                        throw new IllegalStateException("outbox preparation requires a new writable transaction");
                    }
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_COMMITTED) {
                                preparation.committed();
                            }
                        }
                    });
                    return prepareClaimedPublication(claimed.message().descriptor().id(), claimToken, preparation);
                });
                // A locally rollback-only TransactionTemplate may return its callback result normally.
                preparation.requireCommitted();
                preparation.recordRecovery();
                return java.util.Objects.requireNonNull(result, "preparation result");
            } catch (RuntimeException | Error failure) {
                preparation.failed(failure);
                throw failure;
            }
        }
    }

    private Optional<ClaimedOutboxMessage> prepareClaimedPublication(
            MessageId id, String claimToken, OutboxPublicationPreparation preparation) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("messageId", id.value());
        parameters.put("claimToken", claimToken);
        parameters.put("pending", PENDING);
        String owned = "message_id = :messageId AND status = :pending AND claim_token = :claimToken"
                + " AND claim_expires_at > " + claimPreparationTimestamp();
        var rows = jdbc.query("SELECT * FROM moduvera_message_outbox WHERE " + owned + " FOR UPDATE",
                parameters, (resultSet, rowNumber) -> claimedMessage(resultSet));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var current = rows.getFirst();
        var replacement = preparation.replacement(current);
        if (replacement == null) {
            return Optional.of(current);
        }
        parameters.put("publicationParent", replacement.traceParent());
        parameters.put("publicationState", replacement.traceState());
        int updated = jdbc.update("""
                UPDATE moduvera_message_outbox
                   SET publication_traceparent = :publicationParent,
                       publication_tracestate = :publicationState
                 WHERE %s
                """.formatted(owned), parameters);
        if (updated != 1) {
            return Optional.empty();
        }
        preparation.stored(current);
        // Return the persisted metadata, never a temporary SDK Context or the caller's stale snapshot.
        return jdbc.query("SELECT * FROM moduvera_message_outbox WHERE message_id = :messageId",
                parameters, (resultSet, rowNumber) -> claimedMessage(resultSet)).stream().findFirst();
    }

    private String claimPreparationTimestamp() {
        return dialect == JdbcMessagingDialect.MYSQL ? "CURRENT_TIMESTAMP(6)" : "clock_timestamp()";
    }

    private List<ClaimedOutboxMessage> claimPostgresql(
            int limit, Duration lease, String claimToken) {
        return jdbc.query(
                """
                WITH candidates AS (
                    SELECT candidate.message_id
                      FROM moduvera_message_outbox candidate
                     WHERE %s
                     ORDER BY candidate.next_attempt_at, candidate.occurred_at, candidate.message_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT :limit
                )
                UPDATE moduvera_message_outbox AS outbox
                   SET claim_token = :claimToken,
                       claim_expires_at = CURRENT_TIMESTAMP
                           + (:leaseMillis * INTERVAL '1 millisecond')
                  FROM candidates
                 WHERE outbox.message_id = candidates.message_id
                RETURNING outbox.*
                """
                        .formatted(POSTGRESQL_ELIGIBILITY),
                Map.of(
                        "pending", PENDING,
                        "limit", limit,
                        "claimToken", claimToken,
                        "leaseMillis", lease.toMillis()),
                (resultSet, rowNumber) -> claimedMessage(resultSet));
    }

    private List<ClaimedOutboxMessage> claimMySql(
            int limit, Duration lease, String claimToken) {
        return transactions.execute(status -> {
            Map<String, Object> parameters = Map.of("pending", PENDING, "limit", limit);
            List<String> messageIds = jdbc.queryForList(
                    """
                    SELECT candidate.message_id
                      FROM moduvera_message_outbox candidate
                     WHERE %s
                     ORDER BY candidate.next_attempt_at, candidate.occurred_at, candidate.message_id
                     LIMIT :limit
                     FOR UPDATE SKIP LOCKED
                    """
                            .formatted(MYSQL_ELIGIBILITY),
                    parameters,
                    String.class);
            if (messageIds.isEmpty()) {
                return List.of();
            }
            jdbc.update(
                    """
                    UPDATE moduvera_message_outbox
                       SET claim_token = :claimToken,
                           claim_expires_at = TIMESTAMPADD(
                               MICROSECOND, :leaseMicros, CURRENT_TIMESTAMP(6))
                     WHERE message_id IN (:messageIds)
                    """,
                    Map.of(
                            "claimToken", claimToken,
                            "leaseMicros", lease.toMillis() * 1_000L,
                            "messageIds", messageIds));
            return jdbc.query(
                    """
                    SELECT *
                      FROM moduvera_message_outbox
                     WHERE claim_token = :claimToken
                     ORDER BY next_attempt_at, occurred_at, message_id
                    """,
                    Map.of("claimToken", claimToken),
                    (resultSet, rowNumber) -> claimedMessage(resultSet));
        });
    }

    @Override
    public boolean claimContentionObserved() {
        String query = dialect == JdbcMessagingDialect.MYSQL
                ? """
                  SELECT EXISTS (
                      SELECT 1
                        FROM moduvera_message_outbox candidate
                       WHERE %s
                  )
                  """
                        .formatted(MYSQL_ELIGIBILITY)
                : """
                  SELECT EXISTS (
                      SELECT 1
                        FROM moduvera_message_outbox candidate
                       WHERE %s
                  )
                  """
                        .formatted(POSTGRESQL_ELIGIBILITY);
        return Boolean.TRUE.equals(
                jdbc.queryForObject(query, Map.of("pending", PENDING), Boolean.class));
    }

    @Override
    public boolean markPublished(MessageId id, String claimToken, Instant publishedAt) {
        return jdbc.update(
                        """
                        UPDATE moduvera_message_outbox
                           SET status = :published,
                               published_at = :publishedAt,
                               claim_token = NULL,
                               claim_expires_at = NULL,
                               last_failure = NULL
                         WHERE message_id = :messageId
                           AND status = :pending
                           AND claim_token = :claimToken
                        """,
                        Map.of(
                                "published", PUBLISHED,
                                "publishedAt", Timestamp.from(publishedAt),
                                "messageId", id.value(),
                                "pending", PENDING,
                                "claimToken", claimToken))
                == 1;
    }

    @Override
    public boolean markTerminal(
            MessageId id, String claimToken, Instant terminalAt, String safeFailure) {
        return jdbc.update(
                        """
                        UPDATE moduvera_message_outbox
                           SET status = :terminal,
                               terminal_at = :terminalAt,
                               claim_expires_at = NULL,
                               last_failure = :lastFailure,
                               attempt_count = attempt_count + 1
                         WHERE message_id = :messageId
                           AND status = :pending
                           AND claim_token = :claimToken
                        """,
                        Map.of(
                                "terminal", TERMINAL,
                                "terminalAt", Timestamp.from(terminalAt),
                                "lastFailure", boundedFailure(safeFailure),
                                "messageId", id.value(),
                                "pending", PENDING,
                                "claimToken", claimToken))
                == 1;
    }

    @Override
    public boolean markFailed(
            MessageId id, String claimToken, Duration retryDelay, String safeFailure) {
        if (retryDelay == null || retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        return jdbc.update(
                        ("""
                        UPDATE moduvera_message_outbox
                           SET next_attempt_at = %s,
                               claim_token = NULL,
                               claim_expires_at = NULL,
                               last_failure = :lastFailure,
                               attempt_count = attempt_count + 1
                         WHERE message_id = :messageId
                           AND status = :pending
                           AND claim_token = :claimToken
                        """)
                                .formatted(databaseRetryTimestamp()),
                        Map.of(
                                "retryDelayMicros", retryDelay.toNanos() / 1_000,
                                "lastFailure", boundedFailure(safeFailure),
                                "messageId", id.value(),
                                "pending", PENDING,
                                "claimToken", claimToken))
                == 1;
    }

    private String databaseRetryTimestamp() {
        return dialect == JdbcMessagingDialect.MYSQL
                ? "TIMESTAMPADD(MICROSECOND, :retryDelayMicros, CURRENT_TIMESTAMP(6))"
                : "CURRENT_TIMESTAMP + (:retryDelayMicros * INTERVAL '1 microsecond')";
    }

    @Override
    public List<TerminalOutboxMessage> findTerminal(int limit) {
        requireLimit(limit);
        return jdbc.query(
                """
                SELECT message_id, message_type, destination, terminal_at, last_failure, claim_token
                  FROM moduvera_message_outbox
                 WHERE status = :terminal
                 ORDER BY terminal_at, message_id
                 LIMIT :limit
                """,
                Map.of("terminal", TERMINAL, "limit", limit),
                (resultSet, rowNumber) -> new TerminalOutboxMessage(
                        new MessageId(resultSet.getString("message_id")),
                        new MessageType(resultSet.getString("message_type")),
                        new Destination(resultSet.getString("destination")),
                        resultSet.getTimestamp("terminal_at").toInstant(),
                        resultSet.getString("last_failure"),
                        resultSet.getString("claim_token")));
    }

    @Override
    public boolean redrive(MessageId id, String redriveToken) {
        if (redriveToken == null || redriveToken.isBlank()) {
            return false;
        }
        var redrive = new OutboxRedrive();
        transactions.executeWithoutResult(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new IllegalStateException("outbox redrive requires a writable transaction");
            }
            try (redrive) {
                redriveTerminal(id, redriveToken, redrive);
            } catch (RuntimeException | Error failure) {
                redrive.failed(failure);
                throw failure;
            }
        });
        // A joined outer transaction has not decided yet; an owned rollback-only transaction has.
        return redrive.effectiveOrPending();
    }

    private void redriveTerminal(MessageId id, String redriveToken, OutboxRedrive redrive) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("pending", PENDING);
        parameters.put("messageId", id.value());
        parameters.put("terminal", TERMINAL);
        parameters.put("redriveToken", redriveToken);
        String owned = "message_id = :messageId AND status = :terminal AND claim_token = :redriveToken";
        var rows = jdbc.query("SELECT * FROM moduvera_message_outbox WHERE " + owned + " FOR UPDATE",
                parameters, (resultSet, rowNumber) -> claimedMessage(resultSet));
        if (rows.isEmpty()) {
            return;
        }
        var replacement = redrive.start(rows.getFirst().message().descriptor());
        parameters.put("publicationParent", replacement == null ? null : replacement.traceParent());
        parameters.put("publicationState", replacement == null ? null : replacement.traceState());
        int updated = jdbc.update("""
                UPDATE moduvera_message_outbox
                   SET status = :pending,
                       next_attempt_at = CURRENT_TIMESTAMP,
                       terminal_at = NULL,
                       last_failure = NULL,
                       claim_token = NULL,
                       claim_expires_at = NULL,
                       publication_generation = publication_generation + 1,
                       publication_traceparent = :publicationParent,
                       publication_tracestate = :publicationState
                 WHERE %s
                """.formatted(owned), parameters);
        if (updated == 1) {
            redrive.accepted();
            signalWhenVisible();
        }
    }

    @Override
    public int deletePublishedBefore(Instant retentionCutoff, int limit) {
        requireLimit(limit);
        Map<String, Object> parameters = Map.of(
                "published", PUBLISHED,
                "retentionCutoff", Timestamp.from(retentionCutoff),
                "limit", limit);
        if (dialect == JdbcMessagingDialect.MYSQL) {
            return jdbc.update(
                    """
                    DELETE FROM moduvera_message_outbox
                     WHERE message_id IN (
                         SELECT doomed.message_id
                           FROM (
                               SELECT message_id
                                 FROM moduvera_message_outbox
                                WHERE status = :published
                                  AND published_at < :retentionCutoff
                                ORDER BY published_at, message_id
                                LIMIT :limit
                           ) doomed
                     )
                    """,
                    parameters);
        }
        return jdbc.update(
                """
                WITH doomed AS (
                    SELECT message_id
                      FROM moduvera_message_outbox
                     WHERE status = :published
                       AND published_at < :retentionCutoff
                     ORDER BY published_at, message_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT :limit
                )
                DELETE FROM moduvera_message_outbox outbox
                 USING doomed
                 WHERE outbox.message_id = doomed.message_id
                """,
                parameters);
    }

    @Override
    public OutboxBacklog backlog() {
        Timestamp databaseNow = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Map.of(), Timestamp.class);
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM moduvera_message_outbox WHERE status = :status",
                Map.of("status", PENDING),
                Long.class);
        Timestamp oldest = jdbc.queryForObject(
                "SELECT MIN(occurred_at) FROM moduvera_message_outbox WHERE status = :status",
                Map.of("status", PENDING),
                Timestamp.class);
        Long terminal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM moduvera_message_outbox WHERE status = :status",
                Map.of("status", TERMINAL),
                Long.class);
        Duration oldestAge = oldest == null || oldest.after(databaseNow)
                ? Duration.ZERO
                : Duration.between(oldest.toInstant(), databaseNow.toInstant());
        return new OutboxBacklog(pending, oldestAge, terminal);
    }

    private void signalWhenVisible() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wakeSignal.signal();
                }
            });
            return;
        }
        wakeSignal.signal();
    }

    private static ClaimedOutboxMessage claimedMessage(ResultSet resultSet) throws SQLException {
        String causationId = resultSet.getString("causation_id");
        String creationParent = resultSet.getString("creation_traceparent");
        var descriptor = new MessageDescriptor(
                new MessageId(resultSet.getString("message_id")),
                MessageKind.valueOf(resultSet.getString("message_kind")),
                new MessageType(resultSet.getString("message_type")),
                URI.create(resultSet.getString("source")),
                new Destination(resultSet.getString("destination")),
                resultSet.getTimestamp("occurred_at").toInstant(),
                new TenantId(resultSet.getString("tenant_id")),
                new Actor(
                        ActorType.valueOf(resultSet.getString("actor_type")),
                        resultSet.getString("actor_subject")),
                resultSet.getString("correlation_id"),
                causationId == null ? null : new MessageId(causationId),
                new Initiator(
                        ActorType.valueOf(resultSet.getString("initiator_type")),
                        resultSet.getString("initiator_subject")),
                resultSet.getString("partition_key"),
                creationParent == null ? null : new TraceContextCarrier(creationParent, resultSet.getString("creation_tracestate")));
        var message = new SerializedMessage(
                descriptor, resultSet.getString("content_type"), resultSet.getBytes("payload"));
        return new ClaimedOutboxMessage(message, resultSet.getInt("attempt_count"),
                resultSet.getLong("publication_generation"), resultSet.getString("publication_traceparent"),
                resultSet.getString("publication_tracestate"));
    }

    private static String boundedFailure(String failure) {
        if (failure == null || failure.isBlank()) {
            return "UnknownFailure";
        }
        return failure.length() <= 256 ? failure : failure.substring(0, 256);
    }

    private static void requireClaimArguments(int limit, Duration lease) {
        requireLimit(limit);
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("claim lease must be positive");
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

}
