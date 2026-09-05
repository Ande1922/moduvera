package io.github.ande1922.moduvera.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.context.ActorType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ModuveraJwtAuthenticationConverterTest {

    private final ModuveraJwtAuthenticationConverter converter =
            new ModuveraJwtAuthenticationConverter(new JwtExecutionContextFactory());

    @Test
    void convertsTrustedClaimsIntoActorTenantAndAuthorities() {
        Jwt jwt = jwt(ActorType.USER, "user-42")
                .claim(ModuveraJwtClaims.TENANT_ID, "tenant-a")
                .claim(ModuveraJwtClaims.PERMISSIONS, List.of("order:create", "order:read"))
                .build();

        var authentication = (ModuveraJwtAuthenticationToken) converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("user-42");
        assertThat(authentication.trustedPrincipal().actor().type()).isEqualTo(ActorType.USER);
        assertThat(authentication.trustedPrincipal().assertedTenant()).hasValueSatisfying(
                tenant -> assertThat(tenant.value()).isEqualTo("tenant-a"));
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("PERM_order:create", "PERM_order:read");
    }

    @Test
    void authenticatesAUserTokenWithoutATenantClaim() {
        Jwt jwt = jwt(ActorType.USER, "user-42").build();

        var authentication = (ModuveraJwtAuthenticationToken) converter.convert(jwt);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.trustedPrincipal().actor().subjectId()).isEqualTo("user-42");
        assertThat(authentication.trustedPrincipal().initiator().subjectId()).isEqualTo("user-42");
        assertThat(authentication.trustedPrincipal().assertedTenant()).isEmpty();
    }

    @Test
    void preservesAnExplicitInitiatorForServiceWork() {
        Jwt jwt = jwt(ActorType.SERVICE, "order-service")
                .claim(ModuveraJwtClaims.PERMISSIONS, List.of("inventory:reserve"))
                .claim(ModuveraJwtClaims.INITIATOR_TYPE, "USER")
                .claim(ModuveraJwtClaims.INITIATOR_ID, "user-42")
                .build();

        var authentication = (ModuveraJwtAuthenticationToken) converter.convert(jwt);

        assertThat(authentication.trustedPrincipal().initiator().type()).isEqualTo(ActorType.USER);
        assertThat(authentication.trustedPrincipal().initiator().subjectId()).isEqualTo("user-42");
    }

    private static Jwt.Builder jwt(ActorType actorType, String subject) {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(ModuveraJwtClaims.ACTOR_TYPE, actorType.name());
    }
}
