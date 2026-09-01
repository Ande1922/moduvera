package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.MessageId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface OutboxStore {

    Optional<ClaimedOutboxBatch> claim(int limit, Duration lease);

    default boolean claimContentionObserved() {
        return false;
    }

    boolean markPublished(MessageId id, String claimToken, Instant publishedAt);

    boolean markFailed(MessageId id, String claimToken, Duration retryDelay, String safeFailure);

    boolean markTerminal(MessageId id, String claimToken, Instant terminalAt, String safeFailure);
}
