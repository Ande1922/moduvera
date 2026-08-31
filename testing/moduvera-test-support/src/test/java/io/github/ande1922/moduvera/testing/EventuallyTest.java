package io.github.ande1922.moduvera.testing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventuallyTest {

    @Test
    void returnsImmediatelyWhenTheConditionIsAlreadyTrue() {
        assertThatCode(() -> Eventually.await(
                        Duration.ofMillis(10), Duration.ofMillis(1), () -> true, () -> "unused"))
                .doesNotThrowAnyException();
    }

    @Test
    void emitsBoundedDiagnosticsOnTimeout() {
        assertThatThrownBy(() -> Eventually.await(
                        Duration.ofMillis(2), Duration.ofMillis(1), () -> false, () -> "last-status=PENDING"))
                .isInstanceOf(EventuallyTimeoutException.class)
                .hasMessageContaining("last-status=PENDING");
    }
}
