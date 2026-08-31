package io.github.ande1922.moduvera.security.jwt;

import java.util.Collection;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class ModuveraJwtAuthenticationToken extends JwtAuthenticationToken {

    private final TrustedJwtPrincipal trustedPrincipal;

    public ModuveraJwtAuthenticationToken(
            Jwt jwt,
            Collection<? extends GrantedAuthority> authorities,
            TrustedJwtPrincipal trustedPrincipal) {
        super(jwt, authorities, trustedPrincipal.actor().subjectId());
        this.trustedPrincipal = Objects.requireNonNull(trustedPrincipal, "trustedPrincipal");
    }

    public TrustedJwtPrincipal trustedPrincipal() {
        return trustedPrincipal;
    }
}
