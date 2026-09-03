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

        try (var ignored = startIdentity(
                "--moduvera.database.migration.mode=startup",
                "--moduvera.database.migration.initialize=true")) {
            assertThat(ignored.isActive()).isTrue();
            assertThat(tenantColumnLength("identity_tenant_membership")).isEqualTo(64);
            assertThat(tenantColumnLength("identity_permission_assignment")).isEqualTo(64);
            assertThat(tenantColumnLength("identity_browser_session")).isEqualTo(64);
        }

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
