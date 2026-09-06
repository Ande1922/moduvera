package io.github.ande1922.moduvera.example.notes.application;

import io.github.ande1922.moduvera.message.MessageId;
import java.time.Instant;

@FunctionalInterface
public interface NoteReceiptStore {

    void record(MessageId messageId, long noteId, Instant receivedAt);
}
