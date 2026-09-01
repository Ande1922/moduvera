package io.github.ande1922.moduvera.reference.inventory.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class InventoryMigrationConfigurationTest {

    @Test
    void publishesTheServiceOwnedDefinitionWithoutExecutingIt() {
        try (var context = new AnnotationConfigApplicationContext(
                InventoryMigrationConfiguration.class)) {
            assertThat(context.getBeansOfType(MigrationDefinition.class))
                    .containsOnlyKeys("inventoryMigrationDefinition");
            assertThat(context.getBean(MigrationDefinition.class))
                    .isEqualTo(new MigrationDefinition(
                            new DatabaseComponent("inventory"),
                            List.of("classpath:db/migration/inventory"),
                            List.of("classpath:db/migration/inventory-mysql"),
                            Map.of()));
        }
    }
}
