package io.github.ande1922.moduvera.reference.catalog.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CatalogMigrationConfigurationTest {

    @Test
    void publishesASideEffectFreeDefinitionForBothSupportedDatabases() {
        new ApplicationContextRunner()
                .withUserConfiguration(CatalogMigrationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MigrationDefinition.class);
                    assertThat(context).doesNotHaveBean(DataSource.class);

                    var definition = context.getBean(MigrationDefinition.class);
                    assertThat(definition.component().value()).isEqualTo("catalog");
                    assertThat(definition.postgresqlLocations())
                            .containsExactly("classpath:db/migration/catalog");
                    assertThat(definition.mysqlLocations())
                            .containsExactly("classpath:db/migration/catalog-mysql");
                    assertThat(definition.placeholders()).isEqualTo(Map.of());
                });
    }
}
