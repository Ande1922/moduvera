package io.github.ande1922.moduvera.api;

import java.util.List;

public record CursorResult<T>(List<T> content, String nextCursor) {

    public CursorResult {
        content = List.copyOf(content);
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor must be null or non-blank");
        }
    }

    public boolean hasMore() {
        return nextCursor != null;
    }
}
