package io.github.ande1922.moduvera.api;

public record CursorRequest(String after, int size) {

    public CursorRequest {
        if (after != null && (after.isBlank() || after.length() > 512)) {
            throw new IllegalArgumentException("cursor must be null or 1-512 characters");
        }
        if (size < 1 || size > PageRequest.MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + PageRequest.MAX_SIZE);
        }
    }
}
