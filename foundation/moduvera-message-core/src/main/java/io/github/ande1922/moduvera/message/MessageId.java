package io.github.ande1922.moduvera.message;

import java.util.Objects;
import java.util.regex.Pattern;

public record MessageId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public MessageId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("message id must be a stable 1-128 character identifier");
        }
    }
}
