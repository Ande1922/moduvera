package io.github.ande1922.moduvera.lock;

import java.util.Optional;
import java.util.function.Supplier;

public final class LockTemplate {

    @FunctionalInterface
    public interface Delay {

        void pause(long millis) throws InterruptedException;
    }

    private final LockProvider provider;
    private final Delay delay;

    public LockTemplate(LockProvider provider) {
        this(provider, Thread::sleep);
    }

    LockTemplate(LockProvider provider, Delay delay) {
        this.provider = provider;
        this.delay = delay;
    }

    public <T> T execute(LockKey key, LockRetryPolicy policy, Supplier<T> callback) {
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            Optional<LockLease> lease = acquire(key, policy);
            if (lease.isPresent()) {
                try (LockLease ignored = lease.orElseThrow()) {
                    return callback.get();
                }
            }
            if (attempt < policy.maxAttempts()) {
                pause(policy.retryDelay().toMillis());
            }
        }
        throw new LockNotAcquiredException(key, policy.maxAttempts());
    }

    private Optional<LockLease> acquire(LockKey key, LockRetryPolicy policy) {
        try {
            return provider.tryAcquire(key, policy.acquisitionTimeout());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LockInterruptedException(interrupted);
        }
    }

    private void pause(long millis) {
        try {
            delay.pause(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LockInterruptedException(interrupted);
        }
    }
}
