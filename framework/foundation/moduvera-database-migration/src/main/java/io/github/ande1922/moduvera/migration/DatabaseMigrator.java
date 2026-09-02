package io.github.ande1922.moduvera.migration;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;

public final class DatabaseMigrator {

    private final DataSource dataSource;
    private final DatabaseIdentityStore identities = new DatabaseIdentityStore();

    public DatabaseMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public MigrateResult migrate(MigrationPlan plan) {
        identities.verifyOrInitialize(dataSource, plan.component(), plan.initialize());
        Flyway flyway = flyway(plan);
        if (plan.initialize() && !historyTableExists(plan.component())) {
            flyway.baseline();
        }
        return flyway.migrate();
    }

    public ValidateResult validate(MigrationPlan plan) {
        identities.verifyOrInitialize(dataSource, plan.component(), false);
        if (!historyTableExists(plan.component())) {
            throw new IllegalStateException(
                    "Flyway history is missing for component " + plan.component().value());
        }
        var flyway = flyway(plan);
        var result = flyway.validateWithResult();
        var pending = flyway.info().pending();
        if (pending.length > 0) {
            throw new IllegalStateException("pending migrations remain for component "
                    + plan.component().value()
                    + ": "
                    + pending.length);
        }
        if (!result.validationSuccessful) {
            throw new IllegalStateException("invalid Flyway history for component "
                    + plan.component().value()
                    + ": "
                    + result.getAllErrorMessages());
        }
        return result;
    }

    public MigrationInfoService info(MigrationPlan plan) {
        identities.verifyOrInitialize(dataSource, plan.component(), false);
        return flyway(plan).info();
    }

    private Flyway flyway(MigrationPlan plan) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(plan.locations().toArray(String[]::new))
                .placeholders(plan.placeholders())
                .table(plan.component().historyTable())
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .baselineDescription("platform component initialization")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load();
    }

    private boolean historyTableExists(DatabaseComponent component) {
        try (var connection = dataSource.getConnection();
                var tables = connection.getMetaData()
                        .getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                if (component.historyTable().equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    return true;
                }
            }
            return false;
        } catch (SQLException exception) {
            throw new DatabaseIdentityException("could not inspect Flyway history table", exception);
        }
    }
}
