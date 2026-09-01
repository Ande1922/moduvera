package io.github.ande1922.moduvera.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProgressBarrierTest {

    @Test
    void capturesThePreActionBaselineAndRequiresStrictProgress() {
        var progress = new AtomicLong(4);
        ProgressBarrier barrier = ProgressBarrier.capture(progress::get);
        progress.set(6);

        assertThatCode(() -> barrier.awaitAdvanceBy(
                        2, Duration.ofMillis(10), Duration.ofMillis(1), () -> "consumer=inventory"))
                .doesNotThrowAnyException();
        assertThat(barrier.baseline()).isEqualTo(4);
    }

    @Test
    void anAlreadyTrueBusinessStateCannotSatisfyTheProgressBarrier() {
        var progress = new AtomicLong(4);
        ProgressBarrier barrier = ProgressBarrier.capture(progress::get);

        assertThatThrownBy(() -> barrier.awaitAdvanceBy(
                        1, Duration.ofMillis(2), Duration.ofMillis(1), () -> "consumer=inventory"))
                .isInstanceOf(EventuallyTimeoutException.class)
                .hasMessageContaining("baseline=4", "target=5", "lastObserved=4", "consumer=inventory");
    }

    @Test
    void requiresPositiveMonotonicProgress() {
        var progress = new AtomicLong(4);
        ProgressBarrier barrier = ProgressBarrier.capture(progress::get);

        assertThatThrownBy(() -> barrier.awaitAdvanceBy(
                        0, Duration.ofMillis(10), Duration.ofMillis(1), () -> "unused"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumAdvance");

        progress.set(3);
        assertThatThrownBy(() -> barrier.awaitAdvanceBy(
                        1, Duration.ofMillis(10), Duration.ofMillis(1), () -> "unused"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("progress regressed");
    }
}
