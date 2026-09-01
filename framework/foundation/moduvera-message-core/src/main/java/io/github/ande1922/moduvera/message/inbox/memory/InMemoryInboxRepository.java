package io.github.ande1922.moduvera.message.inbox.memory;

import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.InboxRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public final class InMemoryInboxRepository implements InboxRepository {

    private final Set<Key> processed = new HashSet<>();

    @Override
    public synchronized boolean tryStart(
            TenantId tenantId, String consumerId, MessageId messageId, Instant processedAt) {
        return processed.add(new Key(tenantId, consumerId, messageId));
    }

    private record Key(TenantId tenantId, String consumerId, MessageId messageId) {}
}
