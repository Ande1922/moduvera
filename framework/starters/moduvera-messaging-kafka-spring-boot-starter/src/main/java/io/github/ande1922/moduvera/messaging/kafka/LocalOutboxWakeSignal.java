package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.message.outbox.OutboxWakeSignal;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalOutboxWakeSignal implements OutboxWakeSignal {

    private static final Runnable NOOP = () -> {};

    private final AtomicReference<Runnable> listener = new AtomicReference<>(NOOP);

    @Override
    public void signal() {
        listener.get().run();
    }

    public void listen(Runnable wakeListener) {
        listener.set(Objects.requireNonNull(wakeListener, "wakeListener"));
    }

    public void clear(Runnable wakeListener) {
        listener.compareAndSet(wakeListener, NOOP);
    }
}
