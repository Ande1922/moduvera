package io.github.ande1922.moduvera.migration.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
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
    void selectsTheRuntimeDialectAndMigratesDefinitionsInComponentOrder(
            String dialect, JdbcDatabaseContainer<?> container) throws SQLException {
        DataSource dataSource = new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());

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

    Stream<Arguments> databases() {
        return Stream.of(Arguments.of("mysql", MYSQL), Arguments.of("postgresql", POSTGRESQL));
    }

    private static MigrationDefinition definition(String component) {
        return new MigrationDefinition(
                new DatabaseComponent(component),
                List.of("classpath:db/runtime/postgresql/" + component),
                List.of("classpath:db/runtime/mysql/" + component),
                Map.of());
    }
}
