package io.github.ande1922.moduvera.reference.app.identity;

import io.github.ande1922.moduvera.context.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

public final class IdentityService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityRepository identities;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwt;
    private final IdentityProperties properties;
    private final Clock clock;

    public IdentityService(
            IdentityRepository identities,
            PasswordEncoder passwords,
            JwtEncoder jwt,
            IdentityProperties properties,
            Clock clock) {
        this.identities = identities;
        this.passwords = passwords;
        this.jwt = jwt;
        this.properties = properties;
        this.clock = clock;
    }

    public BrowserSession login(String username, String password, String tenantId) {
        String canonicalTenantId = canonicalTenant(tenantId, IdentityService::invalidCredentials).value();
        var user = identities.userByUsername(username).orElseThrow(IdentityService::invalidCredentials);
        if (!passwords.matches(password, user.passwordHash())
                || !identities.isMember(user.userId(), canonicalTenantId)) {
            throw invalidCredentials();
        }
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        var expiresAt = clock.instant().plus(properties.getSessionTtl());
        identities.saveSession(hash(token), user.userId(), canonicalTenantId, expiresAt);
        return new BrowserSession(token, expiresAt);
    }

    public InternalJwt exchange(
            String authorization, String browserToken, String audience) {
        authenticateService(authorization);
        requireAudience(audience);
        var session = identities.activeSession(hash(browserToken), clock.instant())
                .orElseThrow(IdentityService::invalidSession);
        String canonicalTenantId =
                canonicalTenant(session.tenantId(), IdentityService::invalidSession).value();
        List<String> permissions = identities.permissions(session.userId(), canonicalTenantId);
        var issuedAt = clock.instant();
        var expiresAt = issuedAt.plus(properties.getJwtTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(session.userId())
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("actor_type", "USER")
                .claim("tenant_id", canonicalTenantId)
                .claim("permissions", permissions)
                .claim("initiator_type", "USER")
                .claim("initiator_id", session.userId())
                .build();
        return new InternalJwt(
                jwt.encode(JwtEncoderParameters.from(claims)).getTokenValue(), expiresAt);
    }

    public InternalJwt serviceToken(
            String authorization,
            String tenantId,
            String audience,
            String initiatorType,
            String initiatorId) {
        var service = authenticateService(authorization);
        requireAudience(audience);
        canonicalTenant(tenantId, IdentityService::invalidServiceContext);
        if (initiatorId == null
                || initiatorId.isBlank()
                || !("USER".equals(initiatorType) || "SERVICE".equals(initiatorType))) {
            throw invalidServiceContext();
        }
        List<String> permissions = identities.servicePermissions(service.serviceId(), audience);
        if (permissions.isEmpty()) {
            throw new IdentityException(
                    HttpStatus.FORBIDDEN,
                    "identity.service-not-authorized",
                    "Service is not authorized for the requested audience");
        }
        var issuedAt = clock.instant();
        var expiresAt = issuedAt.plus(properties.getJwtTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(service.serviceId())
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("actor_type", "SERVICE")
                .claim("permissions", permissions)
                .claim("initiator_type", initiatorType)
                .claim("initiator_id", initiatorId)
                .build();
        return new InternalJwt(
                jwt.encode(JwtEncoderParameters.from(claims)).getTokenValue(), expiresAt);
    }

    private IdentityRepository.ServiceAccount authenticateService(String authorization) {
        ServiceCredentials credentials = serviceCredentials(authorization);
        var service = identities.service(credentials.serviceId())
                .orElseThrow(IdentityService::invalidService);
        if (!passwords.matches(credentials.secret(), service.secretHash())) {
            throw invalidService();
        }
        return service;
    }

    private void requireAudience(String audience) {
        if (!properties.getAllowedAudiences().contains(audience)) {
            throw new IdentityException(
                    HttpStatus.BAD_REQUEST, "identity.unsupported-audience", "Audience is not allowed");
        }
    }

    private static ServiceCredentials serviceCredentials(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            throw invalidService();
        }
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 1 || separator == decoded.length() - 1) {
                throw invalidService();
            }
            return new ServiceCredentials(
                    decoded.substring(0, separator), decoded.substring(separator + 1));
        } catch (IllegalArgumentException invalid) {
            throw invalidService();
        }
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IdentityException invalidCredentials() {
        return new IdentityException(
                HttpStatus.UNAUTHORIZED,
                "identity.invalid-credentials",
                "Credentials or tenant membership are invalid");
    }

    private static IdentityException invalidSession() {
        return new IdentityException(
                HttpStatus.UNAUTHORIZED,
                "identity.invalid-session",
                "Browser session is invalid or expired");
    }

    private static IdentityException invalidService() {
        return new IdentityException(
                HttpStatus.UNAUTHORIZED,
                "identity.invalid-service",
                "Service authentication failed");
    }

    private static IdentityException invalidServiceContext() {
        return new IdentityException(
                HttpStatus.BAD_REQUEST,
                "identity.invalid-service-context",
                "Service token context is invalid");
    }

    private static TenantId canonicalTenant(
            String value, Supplier<IdentityException> invalidTenant) {
        try {
            return new TenantId(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw invalidTenant.get();
        }
    }

    public record BrowserSession(String token, java.time.Instant expiresAt) {}

    public record InternalJwt(String token, java.time.Instant expiresAt) {}

    private record ServiceCredentials(String serviceId, String secret) {}
}
