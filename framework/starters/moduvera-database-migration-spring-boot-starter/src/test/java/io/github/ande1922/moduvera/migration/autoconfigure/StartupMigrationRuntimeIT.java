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
import java.util.TreeMap;
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

        assertUnsupportedDefinitionFailsBeforeAnyWrite(dataSource, dialect);
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
        var before = snapshot(dataSource);

        migrationContext(dataSource, "validate", false, missingHistory).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Flyway history")
                    .hasMessageContaining("missing_history");
        });
        assertThat(snapshot(dataSource)).isEqualTo(before);
    }

    private static void assertInvalidHistoryIsRejected(DataSource dataSource) throws SQLException {
        var validHistory = definition(
                "invalid_history", "validated", Map.of("probeTable", "invalid_history_probe"));
        migrationContext(dataSource, "startup", true, validHistory)
                .run(context -> assertThat(context).hasNotFailed());
        var invalidHistory = definition(
                "invalid_history",
                "invalid",
                Map.of("probeTable", "invalid_history_probe", "pendingTable", "invalid_pending_probe"));
        var before = snapshot(dataSource);

        migrationContext(dataSource, "validate", false, invalidHistory).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid Flyway history")
                    .hasMessageContaining("invalid_history");
        });
        assertThat(snapshot(dataSource)).isEqualTo(before);
    }

    private static void assertPendingMigrationsAreRejected(DataSource dataSource) throws SQLException {
        var migratedHistory = definition(
                "pending_history", "validated", Map.of("probeTable", "pending_history_probe"));
        migrationContext(dataSource, "startup", true, migratedHistory)
                .run(context -> assertThat(context).hasNotFailed());
        var pendingHistory = definition(
                "pending_history",
                "pending",
                Map.of("probeTable", "pending_history_probe", "pendingTable", "pending_probe"));
        var before = snapshot(dataSource);

        migrationContext(dataSource, "validate", false, pendingHistory).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("pending migration")
                    .hasMessageContaining("pending_history");
        });
        assertThat(snapshot(dataSource)).isEqualTo(before);
    }

    private static void assertMissingIdentityIsRejectedWithoutWrites(DataSource dataSource) throws SQLException {
        var missingIdentity = definition(
                "missing_identity", "validated", Map.of("probeTable", "missing_identity_probe"));
        var before = snapshot(dataSource);
        migrationContext(dataSource, "validate", false, missingIdentity).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("database is not initialized")
                    .hasMessageContaining("missing_identity");
        });
        assertThat(snapshot(dataSource)).isEqualTo(before);
        assertThat(tableExists(dataSource, "flyway_history_missing_identity")).isFalse();
        assertThat(tableExists(dataSource, "missing_identity_probe")).isFalse();
    }

    private static void assertUnsupportedDefinitionFailsBeforeAnyWrite(DataSource dataSource, String dialect)
            throws SQLException {
        var supported = definition(
                "preflight_alpha", "validated", Map.of("probeTable", "preflight_alpha_probe"));
        var unsupported = new MigrationDefinition(
                new DatabaseComponent("preflight_zeta"),
                dialect.equals("postgresql")
                        ? List.of()
                        : List.of("classpath:db/runtime/postgresql/validated"),
                dialect.equals("mysql") ? List.of() : List.of("classpath:db/runtime/mysql/validated"),
                Map.of("probeTable", "preflight_zeta_probe"));
        var before = snapshot(dataSource);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModuveraDatabaseMigrationAutoConfiguration.class))
                .withPropertyValues(
                        "moduvera.database.migration.mode=startup",
                        "moduvera.database.migration.initialize=true")
                .withBean(DataSource.class, () -> dataSource)
                .withBean("supportedDefinition", MigrationDefinition.class, () -> supported)
                .withBean("unsupportedDefinition", MigrationDefinition.class, () -> unsupported)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("not supported")
                            .hasMessageContaining("preflight_zeta");
                });

        assertThat(snapshot(dataSource)).isEqualTo(before);
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
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            var catalog = connection.getCatalog();
            var schema = metadata.supportsSchemasInTableDefinitions() ? connection.getSchema() : null;
            var tables = new TreeMap<String, TableSnapshot>();
            try (var results = metadata.getTables(catalog, schema, "%", new String[] {"TABLE", "VIEW"})) {
                while (results.next()) {
                    var table = results.getString("TABLE_NAME");
                    tables.put(
                            table,
                            new TableSnapshot(
                                    results.getString("TABLE_TYPE"),
                                    columns(connection, catalog, schema, table),
                                    rows(connection, table)));
                }
            }
            return new DatabaseSnapshot(Map.copyOf(tables));
        }
    }

    private static List<ColumnSnapshot> columns(
            java.sql.Connection connection, String catalog, String schema, String table) throws SQLException {
        var columns = new ArrayList<ColumnSnapshot>();
        try (var results = connection.getMetaData().getColumns(catalog, schema, table, "%")) {
            while (results.next()) {
                columns.add(new ColumnSnapshot(
                        results.getInt("ORDINAL_POSITION"),
                        results.getString("COLUMN_NAME"),
                        results.getString("TYPE_NAME"),
                        results.getInt("COLUMN_SIZE"),
                        results.getInt("DECIMAL_DIGITS"),
                        results.getInt("NULLABLE"),
                        results.getString("COLUMN_DEF")));
            }
        }
        return List.copyOf(columns);
    }

    private static List<List<String>> rows(java.sql.Connection connection, String table) throws SQLException {
        var rowValues = new ArrayList<List<String>>();
        var quote = connection.getMetaData().getIdentifierQuoteString().strip();
        var quotedTable = quote.isEmpty() ? table : quote + table.replace(quote, quote + quote) + quote;
        try (var statement = connection.createStatement();
                var results = statement.executeQuery("SELECT * FROM " + quotedTable + " ORDER BY 1")) {
            int columns = results.getMetaData().getColumnCount();
            while (results.next()) {
                var row = new ArrayList<String>(columns);
                for (int column = 1; column <= columns; column++) {
                    row.add(Objects.toString(results.getObject(column), null));
                }
                rowValues.add(row);
            }
        }
        rowValues.sort(Comparator.comparing(Object::toString));
        return List.copyOf(rowValues);
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

    private record DatabaseSnapshot(Map<String, TableSnapshot> tables) {}

    private record TableSnapshot(
            String type, List<ColumnSnapshot> columns, List<List<String>> rows) {}

    private record ColumnSnapshot(
            int ordinal,
            String name,
            String type,
            int size,
            int decimals,
            int nullable,
            String defaultValue) {}
}
