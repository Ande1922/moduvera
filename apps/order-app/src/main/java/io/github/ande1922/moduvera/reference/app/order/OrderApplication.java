package io.github.ande1922.moduvera.reference.app.order;

import io.github.ande1922.moduvera.reference.order.configuration.OrderApplicationConfiguration;
import io.github.ande1922.moduvera.reference.order.configuration.OrderPersistenceConfiguration;
import io.github.ande1922.moduvera.reference.order.configuration.RemoteCatalogApiConfiguration;
import io.github.ande1922.moduvera.reference.order.inbound.http.OrderHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.order.inbound.messaging.InventoryResultInboundConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    OrderApplicationConfiguration.class,
    OrderPersistenceConfiguration.class,
    OrderHttpInboundConfiguration.class,
    InventoryResultInboundConfiguration.class,
    RemoteCatalogApiConfiguration.class,
    OrderAppConfiguration.class
})
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
