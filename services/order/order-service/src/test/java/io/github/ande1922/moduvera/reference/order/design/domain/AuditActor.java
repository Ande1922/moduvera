package io.github.ande1922.moduvera.reference.order.design.domain;

import io.github.ande1922.moduvera.context.ActorType;
import java.util.Objects;

public record AuditActor(ActorType type, String subjectId) {

    public AuditActor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectId, "subjectId");
        if (subjectId.isBlank()) {
            throw new IllegalArgumentException("audit subjectId must not be blank");
        }
    }
}
