package io.github.ande1922.moduvera.reference.app.inventory;

import io.github.ande1922.moduvera.messaging.kafka.migration.ModuveraMessagingMigrationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.InventoryModuleConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.messaging.InventoryResultPublicationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.outbound.persistence.InventoryPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.inventory.migration.InventoryMigrationConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    InventoryModuleConfiguration.class,
    InventoryPersistenceConfiguration.class,
    InventoryResultPublicationConfiguration.class,
    ReserveInventoryCommandInboundConfiguration.class,
    InventoryMigrationConfiguration.class,
    ModuveraMessagingMigrationConfiguration.class,
    InventoryAppConfiguration.class
})
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
