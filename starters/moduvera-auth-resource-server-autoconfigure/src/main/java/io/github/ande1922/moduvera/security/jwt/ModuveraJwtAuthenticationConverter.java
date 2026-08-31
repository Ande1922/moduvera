package io.github.ande1922.moduvera.security.jwt;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;

public final class ModuveraJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String PERMISSION_AUTHORITY_PREFIX = "PERM_";

    private final JwtExecutionContextFactory contextFactory;

    public ModuveraJwtAuthenticationConverter(JwtExecutionContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            TrustedJwtPrincipal principal = contextFactory.createPrincipal(jwt);
            List<SimpleGrantedAuthority> authorities = principal.actor().permissions().stream()
                    .sorted()
                    .map(permission -> new SimpleGrantedAuthority(PERMISSION_AUTHORITY_PREFIX + permission))
                    .toList();
            return new ModuveraJwtAuthenticationToken(jwt, authorities, principal);
        } catch (IllegalArgumentException exception) {
            OAuth2Error error = new OAuth2Error(BearerTokenErrorCodes.INVALID_TOKEN);
            throw new OAuth2AuthenticationException(error, "Internal JWT has invalid platform claims", exception);
        }
    }
}
