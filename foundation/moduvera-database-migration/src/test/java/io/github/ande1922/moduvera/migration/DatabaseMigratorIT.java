package io.github.ande1922.moduvera.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class DatabaseMigratorIT {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(System.getProperty("mysql.test.image"));

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image"));

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void enforcesExplicitIdentityAndRunsTheSameMigrationContract(
            String database, JdbcDatabaseContainer<?> container) throws SQLException {
        DataSource dataSource = dataSource(container);
        var component = new DatabaseComponent("probe_" + database);
        var location = "classpath:db/test";
        var probeTable = "migration_probe_" + database;
        var migrator = new DatabaseMigrator(dataSource);
        var guarded = new MigrationPlan(component, java.util.List.of(location), Map.of("probeTable", probeTable), false);

        assertThatThrownBy(() -> migrator.migrate(guarded))
                .isInstanceOf(DatabaseIdentityException.class)
                .hasMessageContaining(component.value());

        var initialized = new MigrationPlan(
                component, java.util.List.of(location), Map.of("probeTable", probeTable), true);
        var migrated = migrator.migrate(initialized);

        assertThat(migrated.success).isTrue();
        assertThat(migrated.migrationsExecuted).isEqualTo(1);
        assertThat(rowCount(dataSource, DatabaseIdentityStore.TABLE)).isEqualTo(1);
        assertThat(rowCount(dataSource, probeTable)).isZero();
        assertThat(migrator.validate(guarded).validationSuccessful).isTrue();
        assertThat(migrator.info(guarded).current().getVersion().getVersion()).isEqualTo("20260830000000");

        var wrongComponent = new MigrationPlan(
                new DatabaseComponent("wrong_" + database), java.util.List.of(location), false);
        assertThatThrownBy(() -> migrator.validate(wrongComponent))
                .isInstanceOf(DatabaseIdentityException.class)
                .hasMessageContaining("wrong_" + database);
    }

    Stream<Arguments> databases() {
        return Stream.of(Arguments.of("mysql", MYSQL), Arguments.of("postgresql", POSTGRESQL));
    }

    private static DataSource dataSource(JdbcDatabaseContainer<?> container) {
        return new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static int rowCount(DataSource dataSource, String table) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

}
