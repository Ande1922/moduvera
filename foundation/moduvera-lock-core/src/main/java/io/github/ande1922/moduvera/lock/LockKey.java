package io.github.ande1922.moduvera.lock;

import io.github.ande1922.moduvera.context.TenantId;
import java.util.Objects;
import java.util.regex.Pattern;

public record LockKey(String value) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public LockKey {
        Objects.requireNonNull(value, "value");
        if (value.length() > 384 || value.isBlank()) {
            throw new IllegalArgumentException("lock key must be 1-384 characters");
        }
    }

    public static LockKey tenant(TenantId tenantId, String resource, String identity) {
        return new LockKey("tenant:" + tenantId.value() + ':' + segment(resource) + ':' + segment(identity));
    }

    public static LockKey global(String resource, String identity) {
        return new LockKey("global:" + segment(resource) + ':' + segment(identity));
    }

    private static String segment(String value) {
        Objects.requireNonNull(value, "lock key segment");
        if (!SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("lock key segment contains unsupported characters");
        }
        return value;
    }
}
