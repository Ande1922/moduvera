package com.gaopc.platform.app.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class IdentityApplicationIT {

    private static final String ISSUER = "https://identity.test";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("postgresql.test.image", "postgres:18.6"));

    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("identity.issuer", () -> ISSUER);
        properties.add("identity.allowed-audiences", () -> "catalog-service,order-service");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private IdentitySigningKeys signingKeys;

    @Autowired
    private JwtEncoder encoder;

    @BeforeEach
    void seedIdentityFixtures() {
        jdbc.update("DELETE FROM identity_browser_session");
        jdbc.update("DELETE FROM identity_permission_assignment");
        jdbc.update("DELETE FROM identity_tenant_membership");
        jdbc.update("DELETE FROM identity_service_permission");
        jdbc.update("DELETE FROM identity_service");
        jdbc.update("DELETE FROM identity_user");

        user("alice", "alice", "alice-password");
        membership("alice", "tenant-a", List.of("catalog:read", "order:create", "order:read"));
        user("bob", "bob", "bob-password");
        membership("bob", "tenant-b", List.of("catalog:read", "order:create", "order:read"));
        user("viewer", "viewer", "viewer-password");
        membership("viewer", "tenant-a", List.of("catalog:read", "order:read"));
        jdbc.update(
                "INSERT INTO identity_service(service_id, secret_hash, enabled) VALUES (?, ?, true)",
                "gateway",
                passwords.encode("gateway-secret"));
        jdbc.update(
                "INSERT INTO identity_service(service_id, secret_hash, enabled) VALUES (?, ?, true)",
                "order-service",
                passwords.encode("order-secret"));
        jdbc.update(
                "INSERT INTO identity_service_permission(service_id, audience, permission) VALUES (?, ?, ?)",
                "order-service",
                "catalog-service",
                "catalog:read");
    }

    @Test
    void issuesOpaqueSessionAndShortLivedSignedInternalJwt() throws Exception {
        HttpResponse<String> login = post(
                "/v1/session/login",
                null,
                """
                {"username":"alice","password":"alice-password","tenantId":"tenant-a"}
                """);
        assertThat(login.statusCode()).isEqualTo(200);
        String sessionToken = JSON.readTree(login.body()).get("token").asString();
        assertThat(sessionToken).hasSize(43).doesNotContain(".");

        HttpResponse<String> exchange = post(
                "/internal/api/v1/token/exchange",
                basic("gateway", "gateway-secret"),
                """
                {"sessionToken":"%s","audience":"order-service"}
                """.formatted(sessionToken));
        assertThat(exchange.statusCode()).isEqualTo(200);
        String accessToken = JSON.readTree(exchange.body()).get("accessToken").asString();
        assertThat(accessToken.chars().filter(character -> character == '.').count()).isEqualTo(2);

        Jwt claims = validatingDecoder("order-service").decode(accessToken);
        assertThat(claims.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.getClaimAsString("actor_type")).isEqualTo("USER");
        assertThat(claims.getClaimAsString("tenant_id")).isEqualTo("tenant-a");
        assertThat(claims.getClaimAsStringList("permissions"))
                .containsExactly("catalog:read", "order:create", "order:read");
        assertThat(claims.getClaimAsString("initiator_id")).isEqualTo("alice");
        assertThat(claims.getExpiresAt()).isBefore(Instant.now().plusSeconds(6 * 60));
    }

    @Test
    void rejectsCredentialTenantGatewayAudienceAndRevokedSessionFailures() throws Exception {
        assertThat(post(
                                "/v1/session/login",
                                null,
                                """
                                {"username":"alice","password":"wrong","tenantId":"tenant-a"}
                                """)
                        .statusCode())
                .isEqualTo(401);
        assertThat(post(
                                "/v1/session/login",
                                null,
                                """
                                {"username":"alice","password":"alice-password","tenantId":"tenant-b"}
                                """)
                        .statusCode())
                .isEqualTo(401);

        String session = login("alice", "alice-password", "tenant-a");
        assertThat(exchange(session, "catalog-service", basic("gateway", "wrong")).statusCode())
                .isEqualTo(401);
        assertThat(exchange(session, "unknown-service", basic("gateway", "gateway-secret"))
                        .statusCode())
                .isEqualTo(400);

        jdbc.update("UPDATE identity_browser_session SET revoked = true");
        assertThat(exchange(session, "catalog-service", basic("gateway", "gateway-secret"))
                        .statusCode())
                .isEqualTo(401);
    }

    @Test
    void resourceServerValidatorRejectsExpiredWrongIssuerAudienceAndSignature() {
        Instant now = Instant.now();
        var decoder = validatingDecoder("order-service");
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "catalog-service", now.minusSeconds(120), now.minusSeconds(60))))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token("https://wrong.test", "order-service", now, now.plusSeconds(60))))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "catalog-service", now, now.plusSeconds(60))))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);

        var otherKeys = new IdentitySigningKeys();
        JwtEncoder otherEncoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(otherKeys.privateJwk())));
        String wrongSignature = otherEncoder.encode(JwtEncoderParameters.from(claims(
                        ISSUER, "order-service", now, now.plusSeconds(60))))
                .getTokenValue();
        assertThatThrownBy(() -> decoder.decode(wrongSignature))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    void issuesAudienceScopedServiceIdentityWhilePreservingOriginalInitiator() throws Exception {
        HttpResponse<String> response = post(
                "/internal/api/v1/service-token",
                basic("order-service", "order-secret"),
                """
                {"tenantId":"tenant-a","audience":"catalog-service",
                 "initiatorType":"USER","initiatorId":"alice"}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        Jwt claims = validatingDecoder("catalog-service")
                .decode(JSON.readTree(response.body()).get("accessToken").asString());
        assertThat(claims.getSubject()).isEqualTo("order-service");
        assertThat(claims.getClaimAsString("actor_type")).isEqualTo("SERVICE");
        assertThat(claims.getClaimAsString("tenant_id")).isNull();
        assertThat(claims.getClaimAsStringList("permissions")).containsExactly("catalog:read");
        assertThat(claims.getClaimAsString("initiator_id")).isEqualTo("alice");

        assertThat(post(
                                "/internal/api/v1/service-token",
                                basic("gateway", "gateway-secret"),
                                """
                                {"tenantId":"tenant-a","audience":"catalog-service",
                                 "initiatorType":"USER","initiatorId":"alice"}
                                """)
                        .statusCode())
                .isEqualTo(403);
    }

    private NimbusJwtDecoder validatingDecoder(String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKeys.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>("aud", values -> values.contains(audience))));
        return decoder;
    }

    private String token(String issuer, String audience, Instant issuedAt, Instant expiresAt) {
        return encoder.encode(JwtEncoderParameters.from(claims(issuer, audience, issuedAt, expiresAt)))
                .getTokenValue();
    }

    private static JwtClaimsSet claims(
            String issuer, String audience, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("alice")
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("actor_type", "USER")
                .claim("tenant_id", "tenant-a")
                .claim("permissions", List.of("order:create"))
                .build();
    }

    private String login(String username, String password, String tenant) throws Exception {
        var response = post(
                "/v1/session/login",
                null,
                """
                {"username":"%s","password":"%s","tenantId":"%s"}
                """.formatted(username, password, tenant));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("token").asString();
    }

    private HttpResponse<String> exchange(String session, String audience, String authorization)
            throws Exception {
        return post(
                "/internal/api/v1/token/exchange",
                authorization,
                """
                {"sessionToken":"%s","audience":"%s"}
                """.formatted(session, audience));
    }

    private HttpResponse<String> post(String path, String authorization, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HTTP.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private void user(String userId, String username, String password) {
        jdbc.update(
                "INSERT INTO identity_user(user_id, username, password_hash, enabled) VALUES (?, ?, ?, true)",
                userId,
                username,
                passwords.encode(password));
    }

    private void membership(String userId, String tenantId, List<String> permissions) {
        jdbc.update(
                "INSERT INTO identity_tenant_membership(user_id, tenant_id) VALUES (?, ?)",
                userId,
                tenantId);
        permissions.forEach(permission -> jdbc.update(
                "INSERT INTO identity_permission_assignment(user_id, tenant_id, permission) VALUES (?, ?, ?)",
                userId,
                tenantId,
                permission));
    }
}
