package io.github.ande1922.moduvera.security.jwt;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import java.util.Objects;
import java.util.Optional;

public record TrustedJwtPrincipal(Actor actor, Initiator initiator, TenantId assertedTenantId) {

    public TrustedJwtPrincipal {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(initiator, "initiator");
        if (actor.type() == ActorType.SYSTEM) {
            throw new IllegalArgumentException("SYSTEM actors cannot enter through the HTTP resource server");
        }
        if (actor.type() == ActorType.USER && assertedTenantId == null) {
            throw new IllegalArgumentException("USER tokens must assert tenant_id");
        }
    }

    public Optional<TenantId> assertedTenant() {
        return Optional.ofNullable(assertedTenantId);
    }
}
