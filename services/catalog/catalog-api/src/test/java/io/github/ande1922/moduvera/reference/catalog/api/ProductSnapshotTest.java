package io.github.ande1922.moduvera.reference.catalog.api;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class ProductSnapshotTest {

    @Test
    void rejectsMoneyThatCannotBeRepresentedByTheContract() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductSnapshot(
                1, "Coffee", new BigDecimal("1.001"), Currency.getInstance("CNY"), 0));
    }
}
