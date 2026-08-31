package io.github.ande1922.moduvera.storage;

import java.util.Objects;
import java.util.regex.Pattern;

public record BucketAlias(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    public BucketAlias {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("bucket alias must be a stable logical lowercase name");
        }
    }
}
