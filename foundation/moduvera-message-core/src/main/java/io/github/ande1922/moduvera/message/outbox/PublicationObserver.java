package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.SerializedMessage;
import java.time.Duration;

public interface PublicationObserver {

    enum Result {
        PUBLISHED,
        RETRY,
        TERMINAL
    }

    default void claimed(int count) {}

    default void completed(SerializedMessage message, Result result, Duration brokerLatency) {}

    default void staleToken(String operation) {}

    default void claimConflict() {}

    default void cleanup(int deleted) {}

    default void backlog(OutboxBacklog backlog) {}

    static PublicationObserver noop() {
        return new PublicationObserver() {};
    }
}
