package io.github.ande1922.moduvera.reference.order.api;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class CreateOrderCommandTest {

    @Test
    void requiresAtLeastOneUniqueProduct() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreateOrderCommand(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new CreateOrderCommand(
                List.of(new CreateOrderLine(1, 1), new CreateOrderLine(1, 2))));
    }
}
