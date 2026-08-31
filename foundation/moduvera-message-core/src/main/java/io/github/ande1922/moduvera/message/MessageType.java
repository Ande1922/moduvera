package io.github.ande1922.moduvera.message;

import java.util.Objects;
import java.util.regex.Pattern;

public record MessageType(String value) {

    private static final Pattern FORMAT =
            Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*\\.v[1-9][0-9]*");

    public MessageType {
        Objects.requireNonNull(value, "value");
        if (value.length() > 192 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("message type must include a stable vN schema suffix");
        }
    }
}
