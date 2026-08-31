package com.gaopc.platform.app.inventory;

import com.gaopc.platform.inventory.configuration.InventoryApplicationConfiguration;
import com.gaopc.platform.inventory.configuration.InventoryPersistenceConfiguration;
import com.gaopc.platform.inventory.inbound.messaging.ReserveInventoryCommandInboundConfiguration;
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
