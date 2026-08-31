package io.github.ande1922.moduvera.reference.inventory.api;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReserveInventoryCommandTest {

    @Test
    void rejectsDuplicateProductsBeforeTheyReachInventoryRules() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReserveInventoryCommand(
                "cmd-1", 42, List.of(new ReserveInventoryLine(7, 1), new ReserveInventoryLine(7, 2))));
    }
}
