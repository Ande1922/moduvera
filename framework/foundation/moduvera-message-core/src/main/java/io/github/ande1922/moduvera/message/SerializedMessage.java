package io.github.ande1922.moduvera.message;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class SerializedMessage {

    public static final String JSON = "application/json";

    private final MessageDescriptor descriptor;
    private final String contentType;
    private final byte[] payload;

    public SerializedMessage(MessageDescriptor descriptor, String contentType, byte[] payload) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        this.contentType = contentType;
        this.payload = Arrays.copyOf(payload, payload.length);
    }

    public static SerializedMessage json(MessageDescriptor descriptor, String json) {
        return new SerializedMessage(descriptor, JSON, json.getBytes(StandardCharsets.UTF_8));
    }

    public MessageDescriptor descriptor() {
        return descriptor;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
