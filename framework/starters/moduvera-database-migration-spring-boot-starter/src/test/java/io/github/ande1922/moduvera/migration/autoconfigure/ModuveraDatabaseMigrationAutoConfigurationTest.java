package io.github.ande1922.moduvera.migration.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.AbstractDataSource;

class ModuveraDatabaseMigrationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModuveraDatabaseMigrationAutoConfiguration.class));

    @Test
    void defaultPolicyStartsWithoutADataSource() {
        runner.withBean(MigrationDefinition.class, () -> definition("catalog"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ModuveraDatabaseMigrationProperties.class).getMode())
                            .isEqualTo(ModuveraDatabaseMigrationMode.DISABLED);
                    assertThat(context.getBean(ModuveraDatabaseMigrationProperties.class).isInitialize())
                            .isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"external", "disabled"})
    void noRunPoliciesStartWithSelectedDefinitionsAndWithoutADataSource(String mode) {
        runner.withPropertyValues("moduvera.database.migration.mode=" + mode)
                .withBean(MigrationDefinition.class, () -> definition("catalog"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startupRejectsDuplicateComponentsBeforeTouchingTheDataSource() {
        var dataSource = new RejectingDataSource();

        runner.withPropertyValues("moduvera.database.migration.mode=startup")
                .withBean(DataSource.class, () -> dataSource)
                .withBean("firstDefinition", MigrationDefinition.class, () -> definition("catalog"))
                .withBean("secondDefinition", MigrationDefinition.class, () -> definition("catalog"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("duplicate migration component catalog");
                    assertThat(dataSource.connectionRequests).isZero();
                });
    }

    @Test
    void validateRejectsInitializationBeforeTouchingTheDataSource() {
        var dataSource = new RejectingDataSource();

        runner.withPropertyValues(
                        "moduvera.database.migration.mode=validate",
                        "moduvera.database.migration.initialize=true")
                .withBean(DataSource.class, () -> dataSource)
                .withBean(MigrationDefinition.class, () -> definition("catalog"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("VALIDATE migration mode does not allow initialize=true");
                    assertThat(dataSource.connectionRequests).isZero();
                });
    }

    private static MigrationDefinition definition(String component) {
        return new MigrationDefinition(
                new DatabaseComponent(component),
                List.of("classpath:db/test/postgresql"),
                List.of("classpath:db/test/mysql"),
                Map.of());
    }

    private static final class RejectingDataSource extends AbstractDataSource {

        private int connectionRequests;

        @Override
        public Connection getConnection() throws SQLException {
            connectionRequests++;
            throw new SQLException("connection must not be requested");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }
}
