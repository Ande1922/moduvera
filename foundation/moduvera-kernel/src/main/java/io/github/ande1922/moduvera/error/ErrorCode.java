package io.github.ande1922.moduvera.error;

import java.util.Objects;
import java.util.regex.Pattern;

public record ErrorCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    public ErrorCode {
        Objects.requireNonNull(value, "value");
        if (value.length() > 128 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("error code must be a stable lowercase identifier");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
