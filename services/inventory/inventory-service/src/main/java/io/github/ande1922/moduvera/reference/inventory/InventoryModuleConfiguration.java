package io.github.ande1922.moduvera.reference.inventory;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryApplicationService;
import io.github.ande1922.moduvera.reference.inventory.application.InventoryResultPublisher;
import io.github.ande1922.moduvera.reference.inventory.domain.AllOrNothingReservationPolicy;
import io.github.ande1922.moduvera.reference.inventory.domain.InventoryStore;
import io.github.ande1922.moduvera.reference.inventory.domain.ReservationPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InventoryModuleConfiguration {

    @Bean
    ReservationPolicy inventoryReservationPolicy() {
        return new AllOrNothingReservationPolicy();
    }

    @Bean
    InventoryApplicationService inventoryApplicationService(
            InventoryStore inventory,
            UseCaseAuthorizer authorizer,
            Clock clock,
            InventoryResultPublisher publisher,
            ReservationPolicy reservationPolicy) {
        return new InventoryApplicationService(
                inventory, authorizer, clock, publisher, reservationPolicy);
    }
}
