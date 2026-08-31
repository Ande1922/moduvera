package io.github.ande1922.moduvera.reference.order.design.domain;

import java.time.Instant;
import java.util.Objects;

public record AuditStamp(Instant at, AuditActor actor) {

    public AuditStamp {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(actor, "actor");
    }
}
