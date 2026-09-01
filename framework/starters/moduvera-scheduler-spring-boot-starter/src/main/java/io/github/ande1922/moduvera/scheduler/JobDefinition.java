package io.github.ande1922.moduvera.scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record JobDefinition(String name, Scope scope, Duration lockTimeout) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public JobDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        if (!NAME.matcher(name).matches() || lockTimeout.isNegative()) {
            throw new IllegalArgumentException("job name or lock timeout is invalid");
        }
    }

    public enum Scope {
        GLOBAL,
        TENANT
    }
}
