package io.github.ande1922.moduvera.reference.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InventoryMessageIdentityTest {

    @Test
    void publishesStableReserveInventoryCommandIdentity() {
        assertThat(ReserveInventoryCommand.MESSAGE_KIND).isEqualTo("ASYNC_COMMAND");
        assertThat(ReserveInventoryCommand.MESSAGE_TYPE)
                .isEqualTo("io.github.ande1922.moduvera.reference.inventory.reserve.v1");
        assertThat(ReserveInventoryCommand.DESTINATION).isEqualTo("inventory.reserve");
    }

    @Test
    void publishesStableInventoryReservationResultIdentity() {
        assertThat(InventoryReservationResult.MESSAGE_KIND).isEqualTo("EVENT");
        assertThat(InventoryReservationResult.MESSAGE_TYPE)
                .isEqualTo("io.github.ande1922.moduvera.reference.inventory.reservation-result.v1");
        assertThat(InventoryReservationResult.DESTINATION).isEqualTo("order.inventory-result");
    }
}
