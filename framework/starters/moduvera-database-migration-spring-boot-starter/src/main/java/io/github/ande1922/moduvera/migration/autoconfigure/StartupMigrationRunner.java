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

final class StartupMigrationRunner implements SmartInitializingSingleton {

    private final List<MigrationDefinition> definitions;
    private final DataSource dataSource;
    private final boolean initialize;

    StartupMigrationRunner(List<MigrationDefinition> definitions, DataSource dataSource, boolean initialize) {
        this.definitions = List.copyOf(definitions);
        this.dataSource = dataSource;
        this.initialize = initialize;
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
            migrator.migrate(MigrationPlanFactory.create(definition, dialect, options));
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
