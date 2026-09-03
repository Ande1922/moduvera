package io.github.ande1922.moduvera.messaging.kafka.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MessagingTenantMigrationIT {

    private static final String POSTGRESQL_LOCATION =
            "classpath:db/moduvera-messaging/postgresql";
    private static final String MYSQL_LOCATION = "classpath:db/moduvera-messaging/mysql";
    private static final String OVERLONG_TENANT = "t".repeat(65);
    private static final String COMPATIBLE_TENANT = "t".repeat(64);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer(System.getProperty("mysql.test.image", "mysql:8.4.11"));

    @Test
    void postgresqlRejectsOverlongHistoryBeforeAlterAndPreservesCompatibleData() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        verifyUpgradeContract(dataSource, POSTGRESQL_LOCATION, "current_schema()", false);
    }

    @Test
    void mysqlRejectsOverlongHistoryBeforeAlterAndPreservesCompatibleData() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());

        verifyUpgradeContract(dataSource, MYSQL_LOCATION, "database()", true);
    }

    private static void verifyUpgradeContract(
            DataSource dataSource, String location, String schemaExpression, boolean mysql) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        reset(jdbc, mysql);
        migrateToV1(dataSource, location);
        insertInbox(jdbc, OVERLONG_TENANT, "overlong-message");

        assertThatThrownBy(() -> migrateLatest(dataSource, location))
                .hasStackTraceContaining(
                        "messaging tenant_id exceeds 64 characters; refusing to narrow persistence contract");
        assertThat(inboxTenant(jdbc, "overlong-message")).isEqualTo(OVERLONG_TENANT);
        assertTenantColumns(jdbc, schemaExpression, 128);

        reset(jdbc, mysql);
        migrateToV1(dataSource, location);
        insertInbox(jdbc, COMPATIBLE_TENANT, "compatible-message");

        migrateLatest(dataSource, location);

        assertThat(inboxTenant(jdbc, "compatible-message")).isEqualTo(COMPATIBLE_TENANT);
        assertTenantColumns(jdbc, schemaExpression, 64);
        assertThatThrownBy(() -> insertInbox(jdbc, COMPATIBLE_TENANT, "compatible-message"))
                .hasMessageContaining("moduvera_message_inbox");
    }

    private static void migrateToV1(DataSource dataSource, String location) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
    }

    private static void migrateLatest(DataSource dataSource, String location) {
        Flyway.configure().dataSource(dataSource).locations(location).load().migrate();
    }

    private static void insertInbox(JdbcTemplate jdbc, String tenant, String message) {
        jdbc.update(
                """
                INSERT INTO moduvera_message_inbox(tenant_id, consumer_id, message_id, processed_at)
                VALUES (?, 'tenant-migration-test', ?, CURRENT_TIMESTAMP)
                """,
                tenant,
                message);
    }

    private static String inboxTenant(JdbcTemplate jdbc, String message) {
        return jdbc.queryForObject(
                "SELECT tenant_id FROM moduvera_message_inbox WHERE message_id = ?",
                String.class,
                message);
    }

    private static void assertTenantColumns(
            JdbcTemplate jdbc, String schemaExpression, int expectedLength) {
        for (String table : new String[] {"moduvera_message_outbox", "moduvera_message_inbox"}) {
            assertThat(jdbc.queryForObject(
                            """
                            SELECT character_maximum_length
                              FROM information_schema.columns
                             WHERE table_schema = %s
                               AND table_name = ?
                               AND column_name = 'tenant_id'
                            """
                                    .formatted(schemaExpression),
                            Integer.class,
                            table))
                    .isEqualTo(expectedLength);
        }
    }

    private static void reset(JdbcTemplate jdbc, boolean mysql) {
        jdbc.execute("DROP TABLE IF EXISTS moduvera_message_inbox");
        jdbc.execute("DROP TABLE IF EXISTS moduvera_message_outbox");
        jdbc.execute("DROP TABLE IF EXISTS flyway_schema_history");
        if (mysql) {
            jdbc.execute("DROP PROCEDURE IF EXISTS moduvera_messaging_assert_tenant_id_length");
        }
    }
}
