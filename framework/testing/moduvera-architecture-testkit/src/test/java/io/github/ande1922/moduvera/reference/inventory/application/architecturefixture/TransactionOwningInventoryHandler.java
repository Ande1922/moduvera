package io.github.ande1922.moduvera.reference.inventory.application.architecturefixture;

import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;

public final class TransactionOwningInventoryHandler implements ApplicationMessageHandler<String> {

    private final InboxTemplate inbox;
    private final TransactionBoundary transactions;

    public TransactionOwningInventoryHandler(InboxTemplate inbox, TransactionBoundary transactions) {
        this.inbox = inbox;
        this.transactions = transactions;
    }

    @Override
    public void handle(String payload, MessageId messageId) {
        transactions.inTransaction(() -> inbox.handle(messageId, payload::length));
    }
}
