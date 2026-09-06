package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

public final class JdbcInboxRepository implements InboxRepository {

    private final NamedParameterJdbcOperations jdbc;
    private final JdbcMessagingDialect dialect;

    public JdbcInboxRepository(NamedParameterJdbcOperations jdbc) {
        this(jdbc, JdbcMessagingDialect.POSTGRESQL);
    }

    public JdbcInboxRepository(NamedParameterJdbcOperations jdbc, JdbcMessagingDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    @Override
    public boolean isProcessed(TenantId tenantId, String consumerId, MessageId messageId) {
        Boolean processed = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM moduvera_message_inbox
                     WHERE tenant_id = :tenantId
                       AND consumer_id = :consumerId
                       AND message_id = :messageId
                )
                """,
                Map.of(
                        "tenantId", tenantId.value(),
                        "consumerId", consumerId,
                        "messageId", messageId.value()),
                Boolean.class);
        return Boolean.TRUE.equals(processed);
    }

    @Override
    public boolean tryStart(
            TenantId tenantId, String consumerId, MessageId messageId, Instant processedAt) {
        String sql = dialect == JdbcMessagingDialect.MYSQL
                ? """
                INSERT IGNORE INTO moduvera_message_inbox
                    (tenant_id, consumer_id, message_id, processed_at)
                VALUES (:tenantId, :consumerId, :messageId, :processedAt)
                """
                : """
                INSERT INTO moduvera_message_inbox
                    (tenant_id, consumer_id, message_id, processed_at)
                VALUES (:tenantId, :consumerId, :messageId, :processedAt)
                ON CONFLICT (tenant_id, consumer_id, message_id) DO NOTHING
                """;
        int inserted = jdbc.update(
                sql,
                Map.of(
                        "tenantId", tenantId.value(),
                        "consumerId", consumerId,
                        "messageId", messageId.value(),
                        "processedAt", Timestamp.from(processedAt)));
        return inserted == 1;
    }
}
