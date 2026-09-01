package io.github.ande1922.moduvera.reference.app.catalog.architecturefixture;

import io.github.ande1922.moduvera.migration.DatabaseMigrator;
import io.github.ande1922.moduvera.migration.MigrationPlan;

public final class LeafAppMigrationExecutorViolation {

    private final DatabaseMigrator migrator;
    private final MigrationPlan plan;

    public LeafAppMigrationExecutorViolation(DatabaseMigrator migrator, MigrationPlan plan) {
        this.migrator = migrator;
        this.plan = plan;
    }

    public DatabaseMigrator migrator() {
        return migrator;
    }

    public MigrationPlan plan() {
        return plan;
    }
}
