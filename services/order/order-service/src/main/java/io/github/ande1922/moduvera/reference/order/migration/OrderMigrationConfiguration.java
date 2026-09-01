package io.github.ande1922.moduvera.reference.order.migration;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderMigrationConfiguration {

    @Bean
    MigrationDefinition orderMigrationDefinition() {
        return new MigrationDefinition(
                new DatabaseComponent("order"),
                List.of("classpath:db/migration/order"),
                List.of("classpath:db/migration/order-mysql"),
                Map.of());
    }
}
