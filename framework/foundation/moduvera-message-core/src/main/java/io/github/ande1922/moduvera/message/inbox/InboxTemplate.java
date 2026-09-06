package io.github.ande1922.moduvera.message.inbox;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import java.time.Clock;

public final class InboxTemplate {

    private final String consumerId;
    private final InboxRepository repository;
    private final TransactionBoundary transactions;
    private final Clock clock;

    public InboxTemplate(
            String consumerId,
            InboxRepository repository,
            TransactionBoundary transactions,
            Clock clock) {
        if (consumerId == null || consumerId.isBlank() || consumerId.length() > 128) {
            throw new IllegalArgumentException("consumerId must be 1-128 characters");
        }
        this.consumerId = consumerId;
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    public boolean isProcessed(MessageId messageId) {
        var tenantId = ExecutionContextHolder.require().tenantId();
        return repository.isProcessed(tenantId, consumerId, messageId);
    }

    public InboxOutcome handle(MessageId messageId, Runnable businessChange) {
        var tenantId = ExecutionContextHolder.require().tenantId();
        return transactions.inTransaction(() -> {
            if (!repository.tryStart(tenantId, consumerId, messageId, clock.instant())) {
                return InboxOutcome.DUPLICATE;
            }
            businessChange.run();
            return InboxOutcome.APPLIED;
        });
    }
}
