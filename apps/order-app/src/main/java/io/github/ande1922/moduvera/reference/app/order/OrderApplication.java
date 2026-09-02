package io.github.ande1922.moduvera.reference.app.order;

import io.github.ande1922.moduvera.messaging.kafka.migration.ModuveraMessagingMigrationConfiguration;
import io.github.ande1922.moduvera.reference.order.OrderModuleConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.http.OrderHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging.InventoryResultInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.IdentityServiceTokenClientConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.RemoteCatalogApiConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.messaging.ReserveInventoryPublicationConfiguration;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence.OrderPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.order.migration.OrderMigrationConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    OrderModuleConfiguration.class,
    OrderPersistenceConfiguration.class,
    ReserveInventoryPublicationConfiguration.class,
    OrderHttpInboundConfiguration.class,
    InventoryResultInboundConfiguration.class,
    IdentityServiceTokenClientConfiguration.class,
    RemoteCatalogApiConfiguration.class,
    OrderMigrationConfiguration.class,
    ModuveraMessagingMigrationConfiguration.class,
    OrderAppConfiguration.class
})
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
