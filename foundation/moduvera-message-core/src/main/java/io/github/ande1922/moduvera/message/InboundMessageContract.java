package io.github.ande1922.moduvera.message;

import io.github.ande1922.moduvera.context.Actor;
import java.net.URI;
import java.util.Objects;

public record InboundMessageContract(
        MessageKind kind,
        MessageType type,
        URI source,
        Destination destination,
        Actor executionActor) {

    public InboundMessageContract {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(executionActor, "executionActor");
        if (!source.isAbsolute()) {
            throw new IllegalArgumentException("expected message source must be absolute");
        }
    }

    public void validate(MessageDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (kind != descriptor.kind()
                || !type.equals(descriptor.type())
                || !source.equals(descriptor.source())
                || !destination.equals(descriptor.destination())) {
            throw new NonRetryableMessageException("message does not match the expected inbound contract");
        }
    }
}
