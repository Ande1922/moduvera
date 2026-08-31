package io.github.ande1922.moduvera.message.outbox;

import java.util.List;

public record ClaimedOutboxBatch(String claimToken, List<ClaimedOutboxMessage> messages) {

    public ClaimedOutboxBatch {
        if (claimToken == null || claimToken.isBlank() || messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("a claimed batch requires a token and at least one message");
        }
        messages = List.copyOf(messages);
    }
}
