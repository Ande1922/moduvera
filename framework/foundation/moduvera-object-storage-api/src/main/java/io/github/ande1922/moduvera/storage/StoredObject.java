package io.github.ande1922.moduvera.storage;

import java.time.Instant;
import java.util.Map;

public record StoredObject(
        ObjectRef reference,
        long size,
        String mediaType,
        Checksum checksum,
        Instant createdAt,
        Map<String, String> metadata) {

    public StoredObject {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        metadata = Map.copyOf(metadata);
    }
}
