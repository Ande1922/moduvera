package io.github.ande1922.moduvera.lock;

import java.time.Duration;
import java.util.Objects;

public record LockRetryPolicy(int maxAttempts, Duration acquisitionTimeout, Duration retryDelay) {

    public LockRetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
        Objects.requireNonNull(acquisitionTimeout, "acquisitionTimeout");
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (acquisitionTimeout.isNegative() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("lock durations must not be negative");
        }
    }

    public static LockRetryPolicy noRetry(Duration acquisitionTimeout) {
        return new LockRetryPolicy(1, acquisitionTimeout, Duration.ZERO);
    }
}
