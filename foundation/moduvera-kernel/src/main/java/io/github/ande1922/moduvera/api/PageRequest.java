package io.github.ande1922.moduvera.api;

import java.util.List;

public record PageRequest(int page, int size, List<SortCriterion> sort) {

    public static final int MAX_SIZE = 200;

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        sort = sort == null ? List.of() : List.copyOf(sort);
    }

    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size, List.of());
    }
}
