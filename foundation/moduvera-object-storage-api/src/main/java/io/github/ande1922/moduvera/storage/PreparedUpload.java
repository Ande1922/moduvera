package io.github.ande1922.moduvera.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public record PreparedUpload(
        ObjectRef reference, URI uploadUri, Instant expiresAt, Map<String, String> requiredHeaders) {

    public PreparedUpload {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }
}
