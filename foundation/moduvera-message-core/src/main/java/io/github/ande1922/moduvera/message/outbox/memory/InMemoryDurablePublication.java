package io.github.ande1922.moduvera.message.outbox.memory;

import io.github.ande1922.moduvera.message.SerializedMessage;
import io.github.ande1922.moduvera.message.publication.DurablePublication;
import java.util.Objects;

/** Deterministic in-memory publication adapter intended for tests and local fakes. */
public final class InMemoryDurablePublication implements DurablePublication {

    private final InMemoryOutboxStore store;

    public InMemoryDurablePublication(InMemoryOutboxStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void append(SerializedMessage message) {
        store.appendIntent(Objects.requireNonNull(message, "message"));
    }
}
