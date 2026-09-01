package io.github.ande1922.moduvera.lock;

public final class LockNotAcquiredException extends IllegalStateException {

    public LockNotAcquiredException(LockKey key, int attempts) {
        super("lock was not acquired after " + attempts + " attempt(s): " + key.value());
    }
}
