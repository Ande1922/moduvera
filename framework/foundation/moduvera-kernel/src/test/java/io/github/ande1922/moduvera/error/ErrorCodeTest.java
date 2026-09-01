package io.github.ande1922.moduvera.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void acceptsStableHierarchicalCodes() {
        assertThat(new ErrorCode("order.stock-rejected").value()).isEqualTo("order.stock-rejected");
    }

    @Test
    void rejectsDisplayTextAndUppercaseCodes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ErrorCode("Order Failed"));
    }
}
