package io.github.ande1922.moduvera.context;

import java.util.Objects;
import java.util.Set;

public record Actor(ActorType type, String subjectId, Set<String> permissions) {

    public Actor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectId, "subjectId");
        if (subjectId.isBlank() || subjectId.length() > 128) {
            throw new IllegalArgumentException("subjectId must be 1-128 characters");
        }
        permissions = Set.copyOf(permissions);
    }

    public Actor(ActorType type, String subjectId) {
        this(type, subjectId, Set.of());
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
