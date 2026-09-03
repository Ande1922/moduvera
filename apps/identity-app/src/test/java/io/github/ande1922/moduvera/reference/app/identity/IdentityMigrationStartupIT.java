package io.github.ande1922.moduvera.reference.app.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationMode;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationProperties;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class IdentityMigrationStartupIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    @Test
    void normalAssemblyValidatesAMigratedDatabaseAndRejectsIncompatibleOrUninitializedState()
            throws SQLException {
        String overlongTenant = "t".repeat(65);
        migrateIdentityToV1();
        execute("INSERT INTO identity_user(user_id, username, password_hash) VALUES ('legacy-user', 'legacy', 'hash')");
        execute("INSERT INTO identity_tenant_membership(user_id, tenant_id) VALUES ('legacy-user', '"
                + overlongTenant + "')");
        assertThatThrownBy(() -> {
                    try (var ignored = startIdentity(
                            "--moduvera.database.migration.mode=startup",
                            "--moduvera.database.migration.initialize=true")) {
                        assertThat(ignored.isActive()).isFalse();
                    }
                })
                .hasStackTraceContaining(
                        "identity tenant_id exceeds 64 characters; refusing to narrow persistence contract");
        assertThat(membershipTenant("legacy-user")).isEqualTo(overlongTenant);
        assertThat(tenantColumnLength("identity_tenant_membership")).isEqualTo(128);
        execute("DROP SCHEMA public CASCADE");
        execute("CREATE SCHEMA public");
        migrateIdentityToV1();
        seedCompatibleIdentityV1Data();

        try (var ignored = startIdentity(
                "--moduvera.database.migration.mode=startup",
                "--moduvera.database.migration.initialize=true")) {
            assertThat(ignored.isActive()).isTrue();
            assertThat(tenantColumnLength("identity_tenant_membership")).isEqualTo(64);
            assertThat(tenantColumnLength("identity_permission_assignment")).isEqualTo(64);
            assertThat(tenantColumnLength("identity_browser_session")).isEqualTo(64);
        }
        assertCompatibleIdentityDataAndConstraints();

        try (var validated = startIdentity()) {
            var migration = validated.getBean(ModuveraDatabaseMigrationProperties.class);
            assertThat(migration.getMode()).isEqualTo(ModuveraDatabaseMigrationMode.VALIDATE);
            assertThat(migration.isInitialize()).isFalse();
        }

        execute("UPDATE flyway_history_identity SET checksum = checksum + 1 WHERE version = '1'");
        assertThatThrownBy(() -> {
                    try (var ignored = startIdentity()) {
                        assertThat(ignored.isActive()).isFalse();
                    }
                })
                .hasStackTraceContaining("invalid Flyway history for component identity");

        execute("DROP SCHEMA public CASCADE");
        execute("CREATE SCHEMA public");
        assertThatThrownBy(() -> {
                    try (var ignored = startIdentity()) {
                        assertThat(ignored.isActive()).isFalse();
                    }
                })
                .hasStackTraceContaining("database is not initialized for component identity");
    }

    private static ConfigurableApplicationContext startIdentity(String... migrationArguments) {
        var arguments = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--identity.issuer=https://identity-migration.test"));
        arguments.addAll(List.of(migrationArguments));
        return new SpringApplicationBuilder(IdentityApplication.class)
                .web(WebApplicationType.SERVLET)
                .logStartupInfo(false)
                .run(arguments.toArray(String[]::new));
    }

    private static void execute(String sql) throws SQLException {
        try (var connection =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrateIdentityToV1() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/identity")
                .table("flyway_history_identity")
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
    }

    private static void seedCompatibleIdentityV1Data() throws SQLException {
        execute("""
                INSERT INTO identity_user(user_id, username, password_hash) VALUES
                    ('compatible-user-a', 'compatible-a', 'hash-a'),
                    ('compatible-user-b', 'compatible-b', 'hash-b')
                """);
        execute("""
                INSERT INTO identity_tenant_membership(user_id, tenant_id) VALUES
                    ('compatible-user-a', 'tenant-a'),
                    ('compatible-user-b', 'tenant-b')
                """);
        execute("""
                INSERT INTO identity_permission_assignment(user_id, tenant_id, permission)
                VALUES ('compatible-user-a', 'tenant-a', 'catalog.read')
                """);
        execute("""
                INSERT INTO identity_browser_session(
                    token_hash, user_id, tenant_id, issued_at, expires_at, revoked)
                VALUES (
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'compatible-user-a', 'tenant-a', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour', FALSE)
                """);
    }

    private static void assertCompatibleIdentityDataAndConstraints() throws SQLException {
        assertThat(rowCount("identity_tenant_membership")).isEqualTo(2);
        assertThat(rowCount("identity_permission_assignment")).isEqualTo(1);
        assertThat(rowCount("identity_browser_session")).isEqualTo(1);
        assertThat(membershipTenant("compatible-user-a")).isEqualTo("tenant-a");

        assertSqlState(
                "INSERT INTO identity_tenant_membership(user_id, tenant_id) "
                        + "VALUES ('compatible-user-a', 'tenant-a')",
                "23505");
        assertSqlState(
                "INSERT INTO identity_permission_assignment(user_id, tenant_id, permission) "
                        + "VALUES ('compatible-user-a', 'tenant-a', 'catalog.read')",
                "23505");
        assertSqlState(
                "INSERT INTO identity_browser_session("
                        + "token_hash, user_id, tenant_id, issued_at, expires_at, revoked) VALUES ("
                        + "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', "
                        + "'compatible-user-a', 'tenant-a', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP + INTERVAL '1 hour', FALSE)",
                "23505");

        execute("INSERT INTO identity_permission_assignment(user_id, tenant_id, permission) "
                + "VALUES ('compatible-user-b', 'tenant-b', 'catalog.read')");
        execute("INSERT INTO identity_browser_session("
                + "token_hash, user_id, tenant_id, issued_at, expires_at, revoked) VALUES ("
                + "'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', "
                + "'compatible-user-b', 'tenant-b', CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP + INTERVAL '1 hour', FALSE)");

        assertSqlState(
                "INSERT INTO identity_permission_assignment(user_id, tenant_id, permission) "
                        + "VALUES ('compatible-user-a', 'tenant-b', 'invalid.cross-tenant')",
                "23503");
        assertSqlState(
                "INSERT INTO identity_browser_session("
                        + "token_hash, user_id, tenant_id, issued_at, expires_at, revoked) VALUES ("
                        + "'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', "
                        + "'compatible-user-a', 'tenant-b', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP + INTERVAL '1 hour', FALSE)",
                "23503");
    }

    private static void assertSqlState(String sql, String expectedSqlState) {
        assertThatThrownBy(() -> execute(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(((SQLException) exception).getSQLState())
                        .isEqualTo(expectedSqlState));
    }

    private static int rowCount(String table) throws SQLException {
        try (var connection =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (!result.next()) {
                throw new SQLException("missing row count for " + table);
            }
            return result.getInt(1);
        }
    }

    private static String membershipTenant(String user) throws SQLException {
        try (var connection =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "SELECT tenant_id FROM identity_tenant_membership WHERE user_id = ?")) {
            statement.setString(1, user);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("missing tenant membership for " + user);
                }
                return result.getString(1);
            }
        }
    }

    private static int tenantColumnLength(String table) throws SQLException {
        try (var connection =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        """
                        SELECT character_maximum_length
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND table_name = ?
                           AND column_name = 'tenant_id'
                        """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("missing tenant_id column for " + table);
                }
                return result.getInt(1);
            }
        }
    }
}
