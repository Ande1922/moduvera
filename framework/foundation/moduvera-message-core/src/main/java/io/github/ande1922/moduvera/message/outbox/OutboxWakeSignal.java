package io.github.ande1922.moduvera.message.outbox;

@FunctionalInterface
public interface OutboxWakeSignal {

    void signal();

    static OutboxWakeSignal noop() {
        return () -> {};
    }
}
