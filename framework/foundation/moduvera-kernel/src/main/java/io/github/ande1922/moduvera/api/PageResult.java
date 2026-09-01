package io.github.ande1922.moduvera.api;

import java.util.List;

public record PageResult<T>(List<T> content, int page, int size, long totalElements, long totalPages) {

    public PageResult {
        content = List.copyOf(content);
        if (page < 0 || size < 1 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("page metadata must not be negative and size must be positive");
        }
        if (content.size() > size || content.size() > totalElements) {
            throw new IllegalArgumentException("page content is inconsistent with page metadata");
        }
        long expectedPages = totalElements == 0 ? 0 : Math.ceilDiv(totalElements, size);
        if (totalPages != expectedPages) {
            throw new IllegalArgumentException("totalPages does not match totalElements and size");
        }
    }

    public static <T> PageResult<T> of(List<T> content, PageRequest request, long totalElements) {
        long totalPages = totalElements == 0 ? 0 : Math.ceilDiv(totalElements, request.size());
        return new PageResult<>(content, request.page(), request.size(), totalElements, totalPages);
    }
}
