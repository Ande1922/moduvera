package io.github.ande1922.moduvera.message.inbox;

import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.MessageId;
import java.time.Instant;

public interface InboxRepository {

    boolean tryStart(TenantId tenantId, String consumerId, MessageId messageId, Instant processedAt);
}
