package io.github.ande1922.moduvera.storage;

import java.util.Objects;
import java.util.Set;

public record UploadPolicy(
        String name,
        long maxBytes,
        Set<String> allowedMediaTypes,
        ObjectVisibility visibility,
        boolean checksumRequired) {

    public UploadPolicy {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("policy name must be 1-64 characters");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        allowedMediaTypes = Set.copyOf(allowedMediaTypes);
        if (allowedMediaTypes.isEmpty() || allowedMediaTypes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("allowedMediaTypes must contain non-blank values");
        }
        Objects.requireNonNull(visibility, "visibility");
    }

    public void validate(long contentLength, String mediaType, Checksum checksum) {
        if (contentLength < 0 || contentLength > maxBytes) {
            throw new IllegalArgumentException("content length exceeds upload policy");
        }
        if (!allowedMediaTypes.contains(mediaType)) {
            throw new IllegalArgumentException("media type is not allowed by upload policy");
        }
        if (checksumRequired && checksum == null) {
            throw new IllegalArgumentException("checksum is required by upload policy");
        }
    }
}
