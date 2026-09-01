package io.github.ande1922.moduvera.messaging.kafka.migration;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModuveraMessagingMigrationConfiguration {

    @Bean
    MigrationDefinition moduveraMessagingMigrationDefinition() {
        return new MigrationDefinition(
                new DatabaseComponent("messaging"),
                List.of("classpath:db/moduvera-messaging/postgresql"),
                List.of("classpath:db/moduvera-messaging/mysql"),
                Map.of());
    }
}
