package io.github.ande1922.moduvera.reference.app.inventory;

import io.github.ande1922.moduvera.reference.inventory.configuration.InventoryApplicationConfiguration;
import io.github.ande1922.moduvera.reference.inventory.configuration.InventoryPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.inventory.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    InventoryApplicationConfiguration.class,
    InventoryPersistenceConfiguration.class,
    ReserveInventoryCommandInboundConfiguration.class,
    InventoryAppConfiguration.class
})
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
