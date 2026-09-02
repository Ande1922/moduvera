package io.github.ande1922.moduvera.migration.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class StartupMigrationRuntimeIT {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(System.getProperty("mysql.test.image"));

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image"));

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void enforcesRuntimeMigrationPoliciesForSupportedDialects(
            String dialect, JdbcDatabaseContainer<?> container) throws SQLException {
        DataSource dataSource = new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());

        assertStartupInitialization(dataSource, dialect);
        assertValidationDoesNotWrite(dataSource);
        assertMissingHistoryIsRejected(dataSource);
        assertInvalidHistoryIsRejected(dataSource);
        assertPendingMigrationsAreRejected(dataSource);
        assertMissingIdentityIsRejectedWithoutWrites(dataSource);
    }

    private static void assertStartupInitialization(DataSource dataSource, String dialect) throws SQLException {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModuveraDatabaseMigrationAutoConfiguration.class))
                .withPropertyValues(
                        "moduvera.database.migration.mode=startup",
                        "moduvera.database.migration.initialize=true")
                .withBean(DataSource.class, () -> dataSource)
                .withBean("zetaDefinition", MigrationDefinition.class, () -> definition("zeta"))
                .withBean("alphaDefinition", MigrationDefinition.class, () -> definition("alpha"))
                .run(context -> assertThat(context).hasNotFailed());

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var results = statement.executeQuery(
                        "SELECT component_name, dialect_name FROM migration_order ORDER BY position")) {
            assertThat(results.next()).isTrue();
            assertThat(results.getString("component_name")).isEqualTo("alpha");
            assertThat(results.getString("dialect_name")).isEqualTo(dialect);
            assertThat(results.next()).isTrue();
            assertThat(results.getString("component_name")).isEqualTo("zeta");
            assertThat(results.getString("dialect_name")).isEqualTo(dialect);
            assertThat(results.next()).isFalse();
        }
    }

    private static void assertValidationDoesNotWrite(DataSource dataSource) throws SQLException {
        var definition = definition("validated", Map.of("probeTable", "validation_probe"));

        migrationContext(dataSource, "startup", true, definition)
                .run(context -> assertThat(context).hasNotFailed());
        var before = snapshot(dataSource);

        migrationContext(dataSource, "validate", false, definition)
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(snapshot(dataSource)).isEqualTo(before);
    }

    private static void assertMissingHistoryIsRejected(DataSource dataSource) throws SQLException {
        var missingHistory = definition(
                "missing_history", "validated", Map.of("probeTable", "missing_history_probe"));
        migrationContext(dataSource, "startup", true, missingHistory)
                .run(context -> assertThat(context).hasNotFailed());
        execute(dataSource, "DROP TABLE flyway_history_missing_history");

        migrationContext(dataSource, "validate", false, missingHistory).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Flyway history")
                    .hasMessageContaining("missing_history");
        });
    }

    private static void assertInvalidHistoryIsRejected(DataSource dataSource) {
        var validHistory = definition(
                "invalid_history", "validated", Map.of("probeTable", "invalid_history_probe"));
        migrationContext(dataSource, "startup", true, validHistory)
                .run(context -> assertThat(context).hasNotFailed());
        var invalidHistory = definition(
                "invalid_history", "invalid", Map.of("probeTable", "invalid_history_probe"));

        migrationContext(dataSource, "validate", false, invalidHistory).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid Flyway history")
                    .hasMessageContaining("invalid_history");
        });
    }

    private static void assertPendingMigrationsAreRejected(DataSource dataSource) {
        var migratedHistory = definition(
                "pending_history", "validated", Map.of("probeTable", "pending_history_probe"));
        migrationContext(dataSource, "startup", true, migratedHistory)
                .run(context -> assertThat(context).hasNotFailed());
        var pendingHistory = definition(
                "pending_history",
                "pending",
                Map.of("probeTable", "pending_history_probe", "pendingTable", "pending_probe"));

        migrationContext(dataSource, "validate", false, pendingHistory).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("pending migration")
                    .hasMessageContaining("pending_history");
        });
    }

    private static void assertMissingIdentityIsRejectedWithoutWrites(DataSource dataSource) throws SQLException {
        var identityRows = rows(dataSource, "moduvera_database_components");
        var missingIdentity = definition(
                "missing_identity", "validated", Map.of("probeTable", "missing_identity_probe"));
        migrationContext(dataSource, "validate", false, missingIdentity).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("database is not initialized")
                    .hasMessageContaining("missing_identity");
        });
        assertThat(rows(dataSource, "moduvera_database_components")).isEqualTo(identityRows);
        assertThat(tableExists(dataSource, "flyway_history_missing_identity")).isFalse();
        assertThat(tableExists(dataSource, "missing_identity_probe")).isFalse();
    }

    Stream<Arguments> databases() {
        return Stream.of(Arguments.of("mysql", MYSQL), Arguments.of("postgresql", POSTGRESQL));
    }

    private static MigrationDefinition definition(String component) {
        return definition(component, Map.of());
    }

    private static MigrationDefinition definition(String component, Map<String, String> placeholders) {
        return definition(component, component, placeholders);
    }

    private static MigrationDefinition definition(
            String component, String resourceComponent, Map<String, String> placeholders) {
        return new MigrationDefinition(
                new DatabaseComponent(component),
                List.of("classpath:db/runtime/postgresql/" + resourceComponent),
                List.of("classpath:db/runtime/mysql/" + resourceComponent),
                placeholders);
    }

    private static ApplicationContextRunner migrationContext(
            DataSource dataSource, String mode, boolean initialize, MigrationDefinition definition) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModuveraDatabaseMigrationAutoConfiguration.class))
                .withPropertyValues(
                        "moduvera.database.migration.mode=" + mode,
                        "moduvera.database.migration.initialize=" + initialize)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(MigrationDefinition.class, () -> definition);
    }

    private static DatabaseSnapshot snapshot(DataSource dataSource) throws SQLException {
        return new DatabaseSnapshot(
                tables(dataSource),
                rows(dataSource, "moduvera_database_components"),
                rows(dataSource, "flyway_history_validated"),
                rows(dataSource, "validation_probe"));
    }

    private static List<String> tables(DataSource dataSource) throws SQLException {
        var tables = new ArrayList<String>();
        try (var connection = dataSource.getConnection();
                var results = connection.getMetaData()
                        .getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (results.next()) {
                var table = results.getString("TABLE_NAME");
                if (table.equalsIgnoreCase("moduvera_database_components")
                        || table.equalsIgnoreCase("flyway_history_validated")
                        || table.equalsIgnoreCase("validation_probe")) {
                    tables.add(table.toLowerCase());
                }
            }
        }
        return tables.stream().sorted().toList();
    }

    private static List<List<String>> rows(DataSource dataSource, String table) throws SQLException {
        var rows = new ArrayList<List<String>>();
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var results = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
            int columns = results.getMetaData().getColumnCount();
            while (results.next()) {
                var row = new ArrayList<String>(columns);
                for (int column = 1; column <= columns; column++) {
                    row.add(Objects.toString(results.getObject(column), null));
                }
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing(Object::toString));
        return List.copyOf(rows);
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean tableExists(DataSource dataSource, String expectedTable) throws SQLException {
        try (var connection = dataSource.getConnection();
                var results = connection.getMetaData()
                        .getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (results.next()) {
                if (expectedTable.equalsIgnoreCase(results.getString("TABLE_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private record DatabaseSnapshot(
            List<String> tables,
            List<List<String>> identities,
            List<List<String>> history,
            List<List<String>> applicationRows) {}
}
