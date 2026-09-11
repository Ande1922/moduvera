package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.MessageId;
import java.time.Instant;
import java.util.List;

public interface OutboxAdministration {

    List<TerminalOutboxMessage> findTerminal(int limit);

    /**
     * Accepts a new publication generation for the matching terminal token.
     * When joined to a caller transaction, true remains conditional on that transaction committing.
     */
    boolean redrive(MessageId id, String redriveToken);

    int deletePublishedBefore(Instant retentionCutoff, int limit);

    OutboxBacklog backlog();
}
