package io.github.ande1922.moduvera.reference.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.migration.MigrationDefinition;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import java.time.Clock;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class InventoryModuleConfigurationTest {

    @Test
    void constructsOnlyTheProtocolNeutralInventoryApplicationService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(InventoryStore.class, () -> (command, now) -> null);
            context.registerBean(InventoryResultPublisher.class, () -> ignored -> {});
            context.registerBean(UseCaseAuthorizer.class, UseCaseAuthorizer::new);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(InventoryModuleConfiguration.class);

            context.refresh();

            assertThat(context.getBeansOfType(InventoryApplicationService.class)).hasSize(1);
            assertThat(context.getBeansOfType(Consumer.class)).isEmpty();
            assertThat(context.getBeansOfType(MigrationDefinition.class)).isEmpty();
            assertThat(context.containsBean("inventoryMapper")).isFalse();
        }
    }
}
