package io.github.ande1922.moduvera.reference.app.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationMode;
import io.github.ande1922.moduvera.migration.autoconfigure.ModuveraDatabaseMigrationProperties;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
        try (var ignored = startIdentity(
                "--moduvera.database.migration.mode=startup",
                "--moduvera.database.migration.initialize=true")) {
            assertThat(ignored.isActive()).isTrue();
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
}
