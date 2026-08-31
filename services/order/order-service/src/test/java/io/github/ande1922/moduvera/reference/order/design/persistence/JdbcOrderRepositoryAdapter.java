package io.github.ande1922.moduvera.reference.order.design.persistence;

import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.order.design.domain.AuditMetadata;
import io.github.ande1922.moduvera.reference.order.design.domain.AuditStamp;
import io.github.ande1922.moduvera.reference.order.design.domain.DemoOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Supplier;

final class JdbcOrderRepositoryAdapter {

    private static final String INSERT_SQL = """
            INSERT INTO entity_audit_order (
                tenant_id, order_id, status, version,
                created_at, created_by_type, created_by_id,
                updated_at, updated_by_type, updated_by_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_SQL = """
            SELECT tenant_id, order_id, status, version,
                   created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id
              FROM entity_audit_order
             WHERE tenant_id = ? AND order_id = ?
            """;

    private static final String UPDATE_SQL = """
            UPDATE entity_audit_order
               SET status = ?, version = ?,
                   updated_at = ?, updated_by_type = ?, updated_by_id = ?
             WHERE tenant_id = ? AND order_id = ? AND version = ?
            """;

    private final JdbcConnectionFactory connections;
    private final Supplier<AuditStamp> auditSource;
    private final OrderRowMapper mapper = new OrderRowMapper();

    JdbcOrderRepositoryAdapter(
            JdbcConnectionFactory connections, Supplier<AuditStamp> auditSource) {
        this.connections = connections;
        this.auditSource = auditSource;
    }

    void initialize(String ddl) throws SQLException {
        try (Connection connection = connections.open();
                var statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    DemoOrder insert(DemoOrder draft) throws SQLException {
        AuditMetadata audit = AuditMetadata.created(databaseStamp());
        DemoOrder stored = draft.persisted(draft.version(), audit);
        OrderRow row = mapper.toRow(stored);
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindInsert(statement, row);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("insert did not affect exactly one row");
            }
        }
        return stored;
    }

    DemoOrder update(DemoOrder candidate) throws SQLException {
        AuditMetadata audit = candidate.audit().updated(databaseStamp());
        DemoOrder stored = candidate.persisted(candidate.version().next(), audit);
        OrderRow row = mapper.toRow(stored);
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            statement.setString(1, row.status());
            statement.setLong(2, row.version());
            setInstant(statement, 3, row.updatedAt());
            statement.setString(4, row.updatedByType());
            statement.setString(5, row.updatedById());
            statement.setString(6, row.tenantId());
            statement.setLong(7, row.orderId());
            statement.setLong(8, candidate.version().value());
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("stale aggregate version");
            }
        }
        return stored;
    }

    Optional<DemoOrder> findById(TenantId tenantId, long orderId) throws SQLException {
        return findRow(tenantId, orderId).map(mapper::toDomain);
    }

    Optional<OrderRow> findRow(TenantId tenantId, long orderId) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, tenantId.value());
            statement.setLong(2, orderId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRow(result)) : Optional.empty();
            }
        }
    }

    private static void bindInsert(PreparedStatement statement, OrderRow row) throws SQLException {
        statement.setString(1, row.tenantId());
        statement.setLong(2, row.orderId());
        statement.setString(3, row.status());
        statement.setLong(4, row.version());
        setInstant(statement, 5, row.createdAt());
        statement.setString(6, row.createdByType());
        statement.setString(7, row.createdById());
        setInstant(statement, 8, row.updatedAt());
        statement.setString(9, row.updatedByType());
        statement.setString(10, row.updatedById());
    }

    private static OrderRow readRow(ResultSet result) throws SQLException {
        return new OrderRow(
                result.getString("tenant_id"),
                result.getLong("order_id"),
                result.getString("status"),
                result.getLong("version"),
                getInstant(result, "created_at"),
                result.getString("created_by_type"),
                result.getString("created_by_id"),
                getInstant(result, "updated_at"),
                result.getString("updated_by_type"),
                result.getString("updated_by_id"));
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        statement.setTimestamp(index, Timestamp.from(value), utcCalendar());
    }

    private static Instant getInstant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column, utcCalendar()).toInstant();
    }

    private static Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private AuditStamp databaseStamp() {
        AuditStamp stamp = auditSource.get();
        return new AuditStamp(stamp.at().truncatedTo(ChronoUnit.MICROS), stamp.actor());
    }
}
