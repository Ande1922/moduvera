package io.github.ande1922.moduvera.lock.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.lock.LockKey;
import io.github.ande1922.moduvera.lock.LockLease;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class LocalLockProviderTest {

    @Test
    void serializesTheSameKeyAndRemovesUnusedLockCells() throws Exception {
        LocalLockProvider provider = new LocalLockProvider();
        LockKey key = LockKey.global("job", "outbox-publisher");

        try (LockLease first = provider.tryAcquire(key, Duration.ZERO).orElseThrow()) {
            assertThat(first).isNotNull();
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(() -> provider.tryAcquire(key, Duration.ZERO)).get()).isEmpty();
            }
        }

        try (LockLease second = provider.tryAcquire(key, Duration.ZERO).orElseThrow()) {
            assertThat(second).isNotNull();
        }
        assertThat(provider.trackedKeyCount()).isZero();
    }
}
