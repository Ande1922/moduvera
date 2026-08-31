package io.github.ande1922.moduvera.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LockTemplateTest {

    private static final LockKey KEY = LockKey.global("outbox", "publisher");

    @Test
    void retriesAcquisitionButExecutesTheCallbackAtMostOnce() {
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicBoolean released = new AtomicBoolean();
        LockProvider provider = (key, timeout) -> acquisitions.incrementAndGet() < 3
                ? Optional.empty()
                : Optional.of(() -> released.set(true));
        LockTemplate template = new LockTemplate(provider, millis -> {});

        String result = template.execute(
                KEY,
                new LockRetryPolicy(3, Duration.ZERO, Duration.ofMillis(1)),
                () -> {
                    callbacks.incrementAndGet();
                    return "published";
                });

        assertThat(result).isEqualTo("published");
        assertThat(acquisitions).hasValue(3);
        assertThat(callbacks).hasValue(1);
        assertThat(released).isTrue();
    }

    @Test
    void doesNotExecuteWhenTheLockCannotBeAcquired() {
        AtomicBoolean called = new AtomicBoolean();
        LockTemplate template = new LockTemplate((key, timeout) -> Optional.empty(), millis -> {});

        assertThatThrownBy(() -> template.execute(
                        KEY,
                        new LockRetryPolicy(2, Duration.ZERO, Duration.ZERO),
                        () -> called.compareAndSet(false, true)))
                .isInstanceOf(LockNotAcquiredException.class);
        assertThat(called).isFalse();
    }
}
