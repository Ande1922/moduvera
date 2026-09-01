package io.github.ande1922.moduvera.messaging.kafka.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.migration.DatabaseComponent;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModuveraMessagingMigrationConfigurationTest {

    @Test
    void explicitlyPublishesTheReliableMessagingMigrationDefinition() {
        new ApplicationContextRunner()
                .withUserConfiguration(ModuveraMessagingMigrationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MigrationDefinition.class);
                    var definition = context.getBean(MigrationDefinition.class);
                    assertThat(definition.component()).isEqualTo(new DatabaseComponent("messaging"));
                    assertThat(definition.postgresqlLocations())
                            .containsExactly("classpath:db/moduvera-messaging/postgresql");
                    assertThat(definition.mysqlLocations())
                            .containsExactly("classpath:db/moduvera-messaging/mysql");
                    assertThat(definition.placeholders()).isEmpty();
                });
    }
}
