package io.github.ande1922.moduvera.message.outbox;

import java.time.Duration;

public record OutboxBacklog(long pendingCount, Duration oldestPendingAge, long terminalCount) {

    public OutboxBacklog {
        if (pendingCount < 0
                || terminalCount < 0
                || oldestPendingAge == null
                || oldestPendingAge.isNegative()) {
            throw new IllegalArgumentException("backlog values must be non-negative");
        }
    }
}
