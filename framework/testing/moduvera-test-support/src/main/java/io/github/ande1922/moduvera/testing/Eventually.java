package io.github.ande1922.moduvera.testing;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class Eventually {

    private Eventually() {}

    public static void await(
            Duration timeout,
            Duration interval,
            BooleanSupplier condition,
            Supplier<String> diagnostics) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(interval, "interval");
        if (timeout.isNegative() || timeout.isZero() || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("eventually timeout and interval must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            pause(interval);
        }
        if (condition.getAsBoolean()) {
            return;
        }
        throw new EventuallyTimeoutException(timeout, diagnostics.get());
    }

    private static void pause(Duration interval) {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("eventually assertion was interrupted", interrupted);
        }
    }
}
