package io.github.ande1922.moduvera.authorization;

import java.util.Objects;
import java.util.regex.Pattern;

public record PermissionCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)+");

    public PermissionCode {
        Objects.requireNonNull(value, "value");
        if (value.length() > 128 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("permission must use stable resource:action segments");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
