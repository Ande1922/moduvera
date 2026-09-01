package io.github.ande1922.moduvera.reference.inventory.migration;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InventoryMigrationConfiguration {

    @Bean
    MigrationDefinition inventoryMigrationDefinition() {
        return new MigrationDefinition(
                new DatabaseComponent("inventory"),
                List.of("classpath:db/migration/inventory"),
                List.of("classpath:db/migration/inventory-mysql"),
                Map.of());
    }
}
