package io.github.ande1922.moduvera.migration;

import java.util.Objects;

public final class MigrationPlanFactory {

    private MigrationPlanFactory() {}

    public static MigrationPlan create(
            MigrationDefinition definition, MigrationDialect dialect, MigrationExecutionOptions options) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(options, "options");
        var locations = switch (dialect) {
            case POSTGRESQL -> definition.postgresqlLocations();
            case MYSQL -> definition.mysqlLocations();
        };
        if (locations.isEmpty()) {
            throw new IllegalArgumentException("migration dialect "
                    + dialect
                    + " is not supported by component "
                    + definition.component().value()
                    + ".");
        }
        return new MigrationPlan(
                definition.component(), locations, definition.placeholders(), options.initialize());
    }
}
