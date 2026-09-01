package io.github.ande1922.moduvera.reference.order.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class OrderMigrationConfigurationTest {

    @Test
    void publishesTheServiceOwnedDefinitionWithoutExecutingIt() {
        try (var context = new AnnotationConfigApplicationContext(
                OrderMigrationConfiguration.class)) {
            assertThat(context.getBeansOfType(MigrationDefinition.class))
                    .containsOnlyKeys("orderMigrationDefinition");
            assertThat(context.getBean(MigrationDefinition.class))
                    .isEqualTo(new MigrationDefinition(
                            new DatabaseComponent("order"),
                            List.of("classpath:db/migration/order"),
                            List.of("classpath:db/migration/order-mysql"),
                            Map.of()));
        }
    }
}
