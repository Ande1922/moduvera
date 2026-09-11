package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.SerializedMessage;

/**
 * A claimed message and its independent persisted publication metadata.
 * Propagation strings remain raw so the runtime adapter can recognize and repair invalid history.
 */
public record ClaimedOutboxMessage(
        SerializedMessage message,
        int failedAttempts,
        long publicationGeneration,
        String publicationTraceParent,
        String publicationTraceState) {

    public ClaimedOutboxMessage {
        if (message == null || failedAttempts < 0 || publicationGeneration < 0) {
            throw new IllegalArgumentException("message and non-negative failure count/generation are required");
        }
    }

    public ClaimedOutboxMessage(SerializedMessage message, int failedAttempts) {
        this(message, failedAttempts, 0, null, null);
    }
}
