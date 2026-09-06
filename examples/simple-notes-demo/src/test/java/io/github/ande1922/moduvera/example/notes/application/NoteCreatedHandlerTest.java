package io.github.ande1922.moduvera.example.notes.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import io.github.ande1922.moduvera.message.inbox.InboxTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class NoteCreatedHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MessageId MESSAGE_ID = new MessageId("note-created-42");

    @Test
    void committedDeliverySkipsTheInboxTransactionAndReceiptWork() {
        var transactions = new RecordingTransactionBoundary();
        var inbox = new RecordingInboxRepository(true, transactions);
        var receipts = new RecordingReceiptStore(transactions);
        var handler = handler(inbox, transactions, receipts);

        ExecutionContextHolder.run(context(), () -> handler.handle(new NoteCreatedEvent(42), MESSAGE_ID));

        assertThat(inbox.prechecks).isOne();
        assertThat(inbox.starts).isZero();
        assertThat(transactions.calls).isZero();
        assertThat(receipts.calls).isZero();
    }

    @Test
    void recordsTheOriginalMessageInsideOneInboxTransaction() {
        var transactions = new RecordingTransactionBoundary();
        var inbox = new RecordingInboxRepository(false, transactions);
        var receipts = new RecordingReceiptStore(transactions);
        var handler = handler(inbox, transactions, receipts);

        ExecutionContextHolder.run(context(), () -> handler.handle(new NoteCreatedEvent(42), MESSAGE_ID));

        assertThat(inbox.prechecks).isOne();
        assertThat(inbox.starts).isOne();
        assertThat(transactions.calls).isOne();
        assertThat(transactions.active).isFalse();
        assertThat(receipts.calls).isOne();
        assertThat(receipts.messageId).isEqualTo(MESSAGE_ID);
        assertThat(receipts.noteId).isEqualTo(42);
        assertThat(receipts.receivedAt).isEqualTo(NOW);
    }

    private static NoteCreatedHandler handler(
            InboxRepository inbox,
            TransactionBoundary transactions,
            NoteReceiptStore receipts) {
        return new NoteCreatedHandler(
                new InboxTemplate("notes-audit", inbox, transactions, CLOCK),
                receipts,
                CLOCK);
    }

    private static ExecutionContext context() {
        return ExecutionContext.initiatedBy(
                new TenantId("tenant-a"),
                new Actor(ActorType.SERVICE, "notes-demo"),
                "corr-note");
    }

    private static final class RecordingInboxRepository implements InboxRepository {
        private final boolean processed;
        private final RecordingTransactionBoundary transactions;
        private int prechecks;
        private int starts;

        private RecordingInboxRepository(
                boolean processed, RecordingTransactionBoundary transactions) {
            this.processed = processed;
            this.transactions = transactions;
        }

        @Override
        public boolean isProcessed(TenantId tenantId, String consumerId, MessageId messageId) {
            assertThat(transactions.active).isFalse();
            prechecks++;
            return processed;
        }

        @Override
        public boolean tryStart(
                TenantId tenantId,
                String consumerId,
                MessageId messageId,
                Instant processedAt) {
            assertThat(transactions.active).isTrue();
            starts++;
            return true;
        }
    }

    private static final class RecordingReceiptStore implements NoteReceiptStore {
        private final RecordingTransactionBoundary transactions;
        private int calls;
        private MessageId messageId;
        private long noteId;
        private Instant receivedAt;

        private RecordingReceiptStore(RecordingTransactionBoundary transactions) {
            this.transactions = transactions;
        }

        @Override
        public void record(MessageId messageId, long noteId, Instant receivedAt) {
            assertThat(transactions.active).isTrue();
            calls++;
            this.messageId = messageId;
            this.noteId = noteId;
            this.receivedAt = receivedAt;
        }
    }

    private static final class RecordingTransactionBoundary implements TransactionBoundary {
        private boolean active;
        private int calls;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            calls++;
            active = true;
            try {
                return work.get();
            } finally {
                active = false;
            }
        }
    }
}
