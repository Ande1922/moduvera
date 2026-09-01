package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.MessageType;
import java.time.Instant;

public record TerminalOutboxMessage(
        MessageId id,
        MessageType type,
        Destination destination,
        Instant terminalAt,
        String safeFailure,
        String redriveToken) {

    public TerminalOutboxMessage {
        if (id == null
                || type == null
                || destination == null
                || terminalAt == null
                || safeFailure == null
                || safeFailure.isBlank()
                || redriveToken == null
                || redriveToken.isBlank()) {
            throw new IllegalArgumentException("complete terminal message diagnostics are required");
        }
    }
}
