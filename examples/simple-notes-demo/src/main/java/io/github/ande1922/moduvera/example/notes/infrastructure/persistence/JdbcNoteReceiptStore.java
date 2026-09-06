package io.github.ande1922.moduvera.example.notes.infrastructure.persistence;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.example.notes.application.NoteReceiptStore;
import io.github.ande1922.moduvera.message.MessageId;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcNoteReceiptStore implements NoteReceiptStore {

    private final JdbcTemplate jdbc;

    public JdbcNoteReceiptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(MessageId messageId, long noteId, Instant receivedAt) {
        var tenantId = ExecutionContextHolder.require().requireTenantId();
        jdbc.update(
                """
                INSERT INTO demo_note_receipt(message_id, tenant_id, note_id, received_at)
                VALUES (?, ?, ?, ?)
                """,
                messageId.value(),
                tenantId.value(),
                noteId,
                Timestamp.from(receivedAt));
    }
}
