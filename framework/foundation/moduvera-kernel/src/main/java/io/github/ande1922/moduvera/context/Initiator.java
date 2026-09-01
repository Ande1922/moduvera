package io.github.ande1922.moduvera.context;

import java.util.Objects;

public record Initiator(ActorType type, String subjectId) {

    public Initiator {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectId, "subjectId");
        if (subjectId.isBlank() || subjectId.length() > 128) {
            throw new IllegalArgumentException("subjectId must be 1-128 characters");
        }
    }

    public static Initiator from(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        return new Initiator(actor.type(), actor.subjectId());
    }
}
