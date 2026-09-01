package io.github.ande1922.moduvera.message;

import java.util.Objects;
import java.util.regex.Pattern;

public record Destination(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    public Destination {
        Objects.requireNonNull(value, "value");
        if (value.length() > 128 || !FORMAT.matcher(value).matches() || value.matches(".*[.-]v[0-9]+$")) {
            throw new IllegalArgumentException("destination must be a logical route without schema version");
        }
    }
}
