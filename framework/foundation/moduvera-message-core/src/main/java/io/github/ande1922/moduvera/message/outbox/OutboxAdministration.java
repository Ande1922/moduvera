package io.github.ande1922.moduvera.message.outbox;

import io.github.ande1922.moduvera.message.MessageId;
import java.time.Instant;
import java.util.List;

public interface OutboxAdministration {

    List<TerminalOutboxMessage> findTerminal(int limit);

    boolean redrive(MessageId id, String redriveToken);

    int deletePublishedBefore(Instant retentionCutoff, int limit);

    OutboxBacklog backlog();
}
