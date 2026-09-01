package io.github.ande1922.moduvera.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record SortCriterion(String field, SortDirection direction) {

    private static final Pattern API_FIELD = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");

    public SortCriterion {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(direction, "direction");
        if (!API_FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("sort field must be a public API field name");
        }
    }
}
