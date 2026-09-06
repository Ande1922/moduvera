package io.github.ande1922.moduvera.example.notes.application;

import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import java.time.Clock;

public final class NoteCreatedHandler implements ApplicationMessageHandler<NoteCreatedEvent> {

    private final InboxTemplate inbox;
    private final NoteReceiptStore receipts;
    private final Clock clock;

    public NoteCreatedHandler(InboxTemplate inbox, NoteReceiptStore receipts, Clock clock) {
        this.inbox = inbox;
        this.receipts = receipts;
        this.clock = clock;
    }

    @Override
    public void handle(NoteCreatedEvent event, MessageId messageId) {
        if (inbox.isProcessed(messageId)) {
            return;
        }
        inbox.handle(messageId, () -> receipts.record(messageId, event.noteId(), clock.instant()));
    }
}
