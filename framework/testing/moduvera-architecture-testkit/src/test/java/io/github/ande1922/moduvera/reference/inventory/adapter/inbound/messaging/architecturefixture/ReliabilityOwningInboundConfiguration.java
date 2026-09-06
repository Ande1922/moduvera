package io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture;

import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;

public final class ReliabilityOwningInboundConfiguration {

    private final InboxTemplate inbox;
    private final TransactionBoundary transactions;

    public ReliabilityOwningInboundConfiguration(
            InboxTemplate inbox, TransactionBoundary transactions) {
        this.inbox = inbox;
        this.transactions = transactions;
    }

    public void accept() {
        transactions.inTransaction(() -> inbox.isProcessed(null));
    }
}
