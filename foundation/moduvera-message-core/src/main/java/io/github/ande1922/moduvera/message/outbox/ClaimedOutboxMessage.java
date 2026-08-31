package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.SerializedMessage;

public record ClaimedOutboxMessage(SerializedMessage message, int failedAttempts) {

    public ClaimedOutboxMessage {
        if (message == null || failedAttempts < 0) {
            throw new IllegalArgumentException("message and non-negative failedAttempts are required");
        }
    }
}
