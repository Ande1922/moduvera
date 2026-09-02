package io.github.ande1922.moduvera.migration.autoconfigure;

import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.migration.MigrationDialect;
import io.github.ande1922.moduvera.migration.MigrationExecutionOptions;
import io.github.ande1922.moduvera.migration.MigrationPlanFactory;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;

final class DatabaseMigrationRunner implements SmartInitializingSingleton {

    private final List<MigrationDefinition> definitions;
    private final DataSource dataSource;
    private final ModuveraDatabaseMigrationMode mode;
    private final boolean initialize;

    DatabaseMigrationRunner(
            List<MigrationDefinition> definitions,
            DataSource dataSource,
            ModuveraDatabaseMigrationMode mode,
            boolean initialize) {
        this.definitions = List.copyOf(definitions);
        this.dataSource = dataSource;
        this.mode = mode;
        this.initialize = initialize;
        if (mode == ModuveraDatabaseMigrationMode.VALIDATE && initialize) {
            throw new IllegalStateException("VALIDATE migration mode does not allow initialize=true");
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        var orderedDefinitions = definitions.stream()
                .sorted(Comparator.comparing(definition -> definition.component().value()))
                .toList();
        var components = new HashSet<String>();
        for (var definition : orderedDefinitions) {
            if (!components.add(definition.component().value())) {
                throw new IllegalStateException(
                        "duplicate migration component " + definition.component().value());
            }
        }
        if (orderedDefinitions.isEmpty()) {
            return;
        }

        var dialect = dialect(dataSource);
        var migrator = new DatabaseMigrator(dataSource);
        var options = new MigrationExecutionOptions(initialize);
        for (var definition : orderedDefinitions) {
            var plan = MigrationPlanFactory.create(definition, dialect, options);
            switch (mode) {
                case STARTUP -> migrator.migrate(plan);
                case VALIDATE -> migrator.validate(plan);
                case EXTERNAL, DISABLED -> throw new IllegalStateException("migration runner requires an execution mode");
            }
        }
    }

    private static MigrationDialect dialect(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return switch (connection.getMetaData().getDatabaseProductName()) {
                case "PostgreSQL" -> MigrationDialect.POSTGRESQL;
                case "MySQL" -> MigrationDialect.MYSQL;
                default -> throw new IllegalStateException("unsupported migration database");
            };
        } catch (SQLException failure) {
            throw new IllegalStateException("could not identify migration database", failure);
        }
    }
}
