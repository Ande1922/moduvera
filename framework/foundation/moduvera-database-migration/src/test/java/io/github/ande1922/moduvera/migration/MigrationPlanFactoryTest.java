package io.github.ande1922.moduvera.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationPlanFactoryTest {

    @Test
    void composesAPostgresqlPlanFromDefinitionAndExecutionOptions() {
        var component = new DatabaseComponent("catalog");
        var definition = new MigrationDefinition(
                component,
                List.of("classpath:db/catalog/postgresql"),
                List.of("classpath:db/catalog/mysql"),
                Map.of("catalogSchema", "catalog"));

        var plan = MigrationPlanFactory.create(
                definition, MigrationDialect.POSTGRESQL, new MigrationExecutionOptions(true));

        assertThat(plan)
                .isEqualTo(new MigrationPlan(
                        component,
                        List.of("classpath:db/catalog/postgresql"),
                        Map.of("catalogSchema", "catalog"),
                        true));
    }

    @Test
    void composesAMysqlPlanFromDefinitionAndExecutionOptions() {
        var component = new DatabaseComponent("catalog");
        var definition = new MigrationDefinition(
                component,
                List.of("classpath:db/catalog/postgresql"),
                List.of("classpath:db/catalog/mysql"),
                Map.of("catalogSchema", "catalog"));

        var plan = MigrationPlanFactory.create(
                definition, MigrationDialect.MYSQL, new MigrationExecutionOptions(false));

        assertThat(plan)
                .isEqualTo(new MigrationPlan(
                        component,
                        List.of("classpath:db/catalog/mysql"),
                        Map.of("catalogSchema", "catalog"),
                        false));
    }
}
