package com.gaopc.platform.app.order;

import com.gaopc.platform.order.configuration.OrderApplicationConfiguration;
import com.gaopc.platform.order.configuration.OrderPersistenceConfiguration;
import com.gaopc.platform.order.configuration.RemoteCatalogApiConfiguration;
import com.gaopc.platform.order.inbound.http.OrderHttpInboundConfiguration;
import com.gaopc.platform.order.inbound.messaging.InventoryResultInboundConfiguration;
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
