package io.github.ande1922.moduvera.reference.order.design.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.order.api.OrderStatus;
import io.github.ande1922.moduvera.reference.order.design.domain.AuditActor;
import io.github.ande1922.moduvera.reference.order.design.domain.AuditStamp;
import io.github.ande1922.moduvera.reference.order.design.domain.DemoOrder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class EntityAuditPersistenceIT {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T01:00:00.123456789Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-30T02:00:00.654321987Z");
    private static final Instant STORED_CREATED_AT = CREATED_AT.truncatedTo(ChronoUnit.MICROS);
    private static final Instant STORED_UPDATED_AT = UPDATED_AT.truncatedTo(ChronoUnit.MICROS);
    private static final TenantId TENANT = new TenantId("tenant-1938123456789012345");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(System.getProperty("mysql.test.image"));

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image"));

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void flattensDomainValueObjectsIntoRealColumnsAndRestoresThem(
            String database, String ddlResource, JdbcDatabaseContainer<?> container)
            throws SQLException, IOException {
        AtomicReference<AuditStamp> audit = new AtomicReference<>(
                new AuditStamp(CREATED_AT, new AuditActor(ActorType.USER, "account-1001")));
        JdbcOrderRepositoryAdapter repository = new JdbcOrderRepositoryAdapter(
                () -> container.createConnection(""), audit::get);
        repository.initialize(readResource(ddlResource));

        DemoOrder inserted = repository.insert(DemoOrder.place(TENANT, 725638912345678901L));
        OrderRow insertedRow = repository.findRow(TENANT, inserted.id()).orElseThrow();

        assertThat(insertedRow)
                .extracting(
                        OrderRow::tenantId,
                        OrderRow::orderId,
                        OrderRow::status,
                        OrderRow::version,
                        OrderRow::createdByType,
                        OrderRow::createdById,
                        OrderRow::updatedByType,
                        OrderRow::updatedById)
                .containsExactly(
                        TENANT.value(),
                        725638912345678901L,
                        "PENDING_STOCK",
                        0L,
                        "USER",
                        "account-1001",
                        "USER",
                        "account-1001");
        assertThat(inserted.audit().createdAt()).isEqualTo(STORED_CREATED_AT);
        assertThat(insertedRow.createdAt()).isEqualTo(STORED_CREATED_AT);
        assertThat(insertedRow.updatedAt()).isEqualTo(STORED_CREATED_AT);

        PhysicalRow insertedPhysical = readPhysicalRow(container, TENANT, inserted.id());
        assertThat(insertedPhysical)
                .isEqualTo(new PhysicalRow(
                        TENANT.value(),
                        inserted.id(),
                        "PENDING_STOCK",
                        0L,
                        STORED_CREATED_AT,
                        "USER",
                        "account-1001",
                        STORED_CREATED_AT,
                        "USER",
                        "account-1001"));

        DemoOrder restored = repository.findById(TENANT, inserted.id()).orElseThrow();
        assertThat(restored.tenantId()).isEqualTo(TENANT);
        assertThat(restored.id()).isEqualTo(inserted.id());
        assertThat(restored.status()).isEqualTo(OrderStatus.PENDING_STOCK);
        assertThat(restored.version().value()).isZero();
        assertThat(restored.audit().createdAt()).isEqualTo(STORED_CREATED_AT);
        assertThat(restored.audit().createdBy())
                .isEqualTo(new AuditActor(ActorType.USER, "account-1001"));
        assertThat(restored.audit().updatedAt()).isEqualTo(STORED_CREATED_AT);
        assertThat(restored.audit().updatedBy())
                .isEqualTo(new AuditActor(ActorType.USER, "account-1001"));

        audit.set(new AuditStamp(
                UPDATED_AT, new AuditActor(ActorType.SERVICE, "inventory-service")));
        DemoOrder updated = repository.update(restored.confirm());
        OrderRow updatedRow = repository.findRow(TENANT, updated.id()).orElseThrow();

        assertThat(updatedRow.status()).isEqualTo("CONFIRMED");
        assertThat(updatedRow.version()).isEqualTo(1L);
        assertThat(updated.audit().updatedAt()).isEqualTo(STORED_UPDATED_AT);
        assertThat(updatedRow.createdAt()).isEqualTo(STORED_CREATED_AT);
        assertThat(updatedRow.createdByType()).isEqualTo("USER");
        assertThat(updatedRow.createdById()).isEqualTo("account-1001");
        assertThat(updatedRow.updatedAt()).isEqualTo(STORED_UPDATED_AT);
        assertThat(updatedRow.updatedByType()).isEqualTo("SERVICE");
        assertThat(updatedRow.updatedById()).isEqualTo("inventory-service");

        PhysicalRow updatedPhysical = readPhysicalRow(container, TENANT, updated.id());
        assertThat(updatedPhysical)
                .isEqualTo(new PhysicalRow(
                        TENANT.value(),
                        updated.id(),
                        "CONFIRMED",
                        1L,
                        STORED_CREATED_AT,
                        "USER",
                        "account-1001",
                        STORED_UPDATED_AT,
                        "SERVICE",
                        "inventory-service"));

        DemoOrder restoredAfterUpdate = repository.findById(TENANT, updated.id()).orElseThrow();
        assertThat(restoredAfterUpdate.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(restoredAfterUpdate.version().value()).isEqualTo(1L);
        assertThat(restoredAfterUpdate.audit().createdAt()).isEqualTo(STORED_CREATED_AT);
        assertThat(restoredAfterUpdate.audit().createdBy())
                .isEqualTo(new AuditActor(ActorType.USER, "account-1001"));
        assertThat(restoredAfterUpdate.audit().updatedAt()).isEqualTo(STORED_UPDATED_AT);
        assertThat(restoredAfterUpdate.audit().updatedBy())
                .isEqualTo(new AuditActor(ActorType.SERVICE, "inventory-service"));

        assertThatThrownBy(() -> repository.update(restored.confirm()))
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessageContaining("stale aggregate version");
    }

    Stream<Arguments> databases() {
        return Stream.of(
                Arguments.of("mysql", "/db/entity-audit-demo/mysql.sql", MYSQL),
                Arguments.of("postgresql", "/db/entity-audit-demo/postgresql.sql", POSTGRESQL));
    }

    private static String readResource(String path) throws IOException {
        try (var stream = EntityAuditPersistenceIT.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(stream, path).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PhysicalRow readPhysicalRow(
            JdbcDatabaseContainer<?> container, TenantId tenantId, long orderId)
            throws SQLException {
        String sql = """
                SELECT tenant_id, order_id, status, version,
                       created_at, created_by_type, created_by_id,
                       updated_at, updated_by_type, updated_by_id
                  FROM entity_audit_order
                 WHERE tenant_id = ? AND order_id = ?
                """;
        try (var connection = container.createConnection("");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setLong(2, orderId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("physical row was not found");
                }
                return new PhysicalRow(
                        result.getString("tenant_id"),
                        result.getLong("order_id"),
                        result.getString("status"),
                        result.getLong("version"),
                        instant(result, "created_at"),
                        result.getString("created_by_type"),
                        result.getString("created_by_id"),
                        instant(result, "updated_at"),
                        result.getString("updated_by_type"),
                        result.getString("updated_by_id"));
            }
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        return result.getTimestamp(column, utc).toInstant();
    }

    private record PhysicalRow(
            String tenantId,
            long orderId,
            String status,
            long version,
            Instant createdAt,
            String createdByType,
            String createdById,
            Instant updatedAt,
            String updatedByType,
            String updatedById) {}
}
