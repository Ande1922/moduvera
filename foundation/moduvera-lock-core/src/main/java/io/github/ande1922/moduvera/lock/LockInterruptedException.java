package io.github.ande1922.moduvera.lock;

public final class LockInterruptedException extends IllegalStateException {

    public LockInterruptedException(InterruptedException cause) {
        super("interrupted while acquiring a lock", cause);
    }
}
