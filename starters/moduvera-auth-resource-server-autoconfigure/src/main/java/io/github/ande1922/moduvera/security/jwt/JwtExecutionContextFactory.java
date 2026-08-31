package io.github.ande1922.moduvera.security.jwt;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtExecutionContextFactory {

    public TrustedJwtPrincipal createPrincipal(Jwt jwt) {
        String subject = requiredString(jwt, "sub");
        ActorType actorType = actorType(requiredString(jwt, ModuveraJwtClaims.ACTOR_TYPE));
        Set<String> permissions = permissions(jwt.getClaim(ModuveraJwtClaims.PERMISSIONS));
        Actor actor = new Actor(actorType, subject, permissions);
        Initiator initiator = initiator(jwt, actor);
        TenantId tenantId = optionalTenant(jwt.getClaim(ModuveraJwtClaims.TENANT_ID));
        return new TrustedJwtPrincipal(actor, initiator, tenantId);
    }

    private static ActorType actorType(String value) {
        try {
            return ActorType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("actor_type must be USER or SERVICE", exception);
        }
    }

    private static Set<String> permissions(Object claim) {
        if (claim == null) {
            return Set.of();
        }
        if (!(claim instanceof Collection<?> values)) {
            throw new IllegalArgumentException("permissions must be an array of permission codes");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String permission)) {
                throw new IllegalArgumentException("permissions must contain only strings");
            }
            result.add(new PermissionCode(permission).value());
        }
        return Set.copyOf(result);
    }

    private static Initiator initiator(Jwt jwt, Actor actor) {
        Object initiatorType = jwt.getClaim(ModuveraJwtClaims.INITIATOR_TYPE);
        Object initiatorId = jwt.getClaim(ModuveraJwtClaims.INITIATOR_ID);
        if (initiatorType == null && initiatorId == null) {
            return Initiator.from(actor);
        }
        if (!(initiatorType instanceof String type) || !(initiatorId instanceof String id)) {
            throw new IllegalArgumentException("initiator_type and initiator_id must be present together");
        }
        return new Initiator(actorType(type), id);
    }

    private static TenantId optionalTenant(Object claim) {
        if (claim == null) {
            return null;
        }
        if (!(claim instanceof String value)) {
            throw new IllegalArgumentException("tenant_id must be a string");
        }
        return new TenantId(value);
    }

    private static String requiredString(Jwt jwt, String claimName) {
        Object claim = jwt.getClaim(claimName);
        if (!(claim instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(claimName + " must be a non-blank string");
        }
        return value;
    }
}
