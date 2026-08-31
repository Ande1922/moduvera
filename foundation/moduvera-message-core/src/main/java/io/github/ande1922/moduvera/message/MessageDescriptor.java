package io.github.ande1922.moduvera.message;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record MessageDescriptor(
        MessageId id,
        MessageKind kind,
        MessageType type,
        URI source,
        Destination destination,
        Instant time,
        TenantId tenantId,
        Actor actor,
        String correlationId,
        MessageId causationId,
        Initiator initiator,
        String partitionKey) {

    public MessageDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(partitionKey, "partitionKey");
        if (!source.isAbsolute()
                || correlationId.isBlank()
                || correlationId.length() > 128
                || partitionKey.isBlank()
                || partitionKey.length() > 256) {
            throw new IllegalArgumentException(
                    "message source must be absolute and correlationId/partitionKey bounded");
        }
    }
}
