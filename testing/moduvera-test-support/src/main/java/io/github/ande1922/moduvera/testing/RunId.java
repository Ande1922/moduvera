package io.github.ande1922.moduvera.testing;

import java.util.UUID;

public record RunId(String value) {

    public RunId {
        if (value == null || !value.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("run id must be a portable lowercase identifier");
        }
    }

    public static RunId random() {
        return new RunId(UUID.randomUUID().toString());
    }
}
