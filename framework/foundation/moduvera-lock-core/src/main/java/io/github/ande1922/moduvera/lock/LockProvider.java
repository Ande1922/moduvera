package io.github.ande1922.moduvera.lock;

import java.time.Duration;
import java.util.Optional;

@FunctionalInterface
public interface LockProvider {

    Optional<LockLease> tryAcquire(LockKey key, Duration timeout) throws InterruptedException;
}
