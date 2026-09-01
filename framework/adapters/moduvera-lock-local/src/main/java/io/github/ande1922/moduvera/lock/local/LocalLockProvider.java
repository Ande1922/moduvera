package io.github.ande1922.moduvera.lock.local;

import io.github.ande1922.moduvera.lock.LockKey;
import io.github.ande1922.moduvera.lock.LockLease;
import io.github.ande1922.moduvera.lock.LockProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class LocalLockProvider implements LockProvider {

    private final ConcurrentMap<LockKey, LockCell> cells = new ConcurrentHashMap<>();

    @Override
    public Optional<LockLease> tryAcquire(LockKey key, Duration timeout) throws InterruptedException {
        LockCell cell = cells.compute(key, (ignored, current) -> {
            LockCell selected = current == null ? new LockCell() : current;
            selected.references.incrementAndGet();
            return selected;
        });

        boolean acquired = false;
        try {
            acquired = cell.lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                releaseReference(key, cell);
                return Optional.empty();
            }
            return Optional.of(new LocalLease(key, cell));
        } catch (InterruptedException interrupted) {
            if (!acquired) {
                releaseReference(key, cell);
            }
            throw interrupted;
        }
    }

    int trackedKeyCount() {
        return cells.size();
    }

    private void releaseReference(LockKey key, LockCell cell) {
        cells.computeIfPresent(key, (ignored, current) -> {
            if (current != cell) {
                return current;
            }
            return current.references.decrementAndGet() == 0 ? null : current;
        });
    }

    private static final class LockCell {

        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger references = new AtomicInteger();
    }

    private final class LocalLease implements LockLease {

        private final LockKey key;
        private final LockCell cell;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LocalLease(LockKey key, LockCell cell) {
            this.key = key;
            this.cell = cell;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                cell.lock.unlock();
                releaseReference(key, cell);
            }
        }
    }
}
