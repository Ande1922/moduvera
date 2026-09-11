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

    /**
     * Prepares the current claimed publication before transport execution. Persistent implementations
     * return database values only after their preparation transaction commits, or empty when the claim
     * no longer admits a send. The default preserves stores without persistent publication metadata.
     */
    default Optional<ClaimedOutboxMessage> preparePublication(
            ClaimedOutboxMessage claimed, String claimToken) {
        return Optional.of(claimed);
    }

    boolean markPublished(MessageId id, String claimToken, Instant publishedAt);

    boolean markFailed(MessageId id, String claimToken, Duration retryDelay, String safeFailure);

    boolean markTerminal(MessageId id, String claimToken, Instant terminalAt, String safeFailure);
}
