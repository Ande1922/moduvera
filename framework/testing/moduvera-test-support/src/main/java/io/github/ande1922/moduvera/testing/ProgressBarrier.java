package io.github.ande1922.moduvera.testing;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class ProgressBarrier {

    private final LongSupplier progress;
    private final long baseline;

    private ProgressBarrier(LongSupplier progress, long baseline) {
        this.progress = progress;
        this.baseline = baseline;
    }

    public static ProgressBarrier capture(LongSupplier progress) {
        Objects.requireNonNull(progress, "progress");
        return new ProgressBarrier(progress, progress.getAsLong());
    }

    public long baseline() {
        return baseline;
    }

    public void awaitAdvanceBy(
            long minimumAdvance,
            Duration timeout,
            Duration interval,
            Supplier<String> diagnostics) {
        if (minimumAdvance <= 0) {
            throw new IllegalArgumentException("minimumAdvance must be positive");
        }
        Objects.requireNonNull(diagnostics, "diagnostics");
        long target = Math.addExact(baseline, minimumAdvance);
        var lastObserved = new AtomicLong(baseline);
        Eventually.await(
                timeout,
                interval,
                () -> {
                    long observed = progress.getAsLong();
                    long previous = lastObserved.getAndSet(observed);
                    if (observed < previous) {
                        throw new IllegalStateException(
                                "monotonic progress regressed from " + previous + " to " + observed);
                    }
                    return observed >= target;
                },
                () -> "baseline=" + baseline + ", target=" + target + ", lastObserved="
                        + lastObserved.get() + ", " + diagnostics.get());
    }
}
