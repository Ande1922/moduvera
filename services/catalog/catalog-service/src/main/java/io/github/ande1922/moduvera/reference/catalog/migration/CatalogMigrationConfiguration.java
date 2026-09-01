package io.github.ande1922.moduvera.reference.catalog.migration;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogMigrationConfiguration {

    @Bean
    MigrationDefinition catalogMigrationDefinition() {
        return new MigrationDefinition(
                new DatabaseComponent("catalog"),
                List.of("classpath:db/migration/catalog"),
                List.of("classpath:db/migration/catalog-mysql"),
                Map.of());
    }
}
