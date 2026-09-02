package io.github.ande1922.moduvera.reference.app.identity.migration;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdentityMigrationConfiguration {

    @Bean
    MigrationDefinition identityMigrationDefinition() {
        return new MigrationDefinition(
                new DatabaseComponent("identity"),
                List.of("classpath:db/migration/identity"),
                List.of(),
                Map.of());
    }
}
