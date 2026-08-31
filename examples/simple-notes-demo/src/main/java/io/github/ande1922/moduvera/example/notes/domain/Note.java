package io.github.ande1922.moduvera.example.notes.domain;

import java.time.Instant;
import java.util.Objects;

public record Note(long id, String content, Instant createdAt) {

    public Note {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        Objects.requireNonNull(content, "content");
        if (content.isBlank() || content.length() > 500) {
            throw new IllegalArgumentException("content must contain 1 to 500 characters");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
