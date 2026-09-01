package io.github.ande1922.moduvera.context;

import java.util.Objects;
import java.util.regex.Pattern;

public record TenantId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public TenantId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("tenantId must be 1-64 portable identifier characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
