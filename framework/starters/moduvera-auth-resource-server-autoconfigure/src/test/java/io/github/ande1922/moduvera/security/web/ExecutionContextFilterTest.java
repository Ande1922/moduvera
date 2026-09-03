package io.github.ande1922.moduvera.security.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.security.jwt.JwtExecutionContextFactory;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationConverter;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationToken;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtClaims;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class ExecutionContextFilterTest {

    private final ModuveraJwtAuthenticationConverter converter =
            new ModuveraJwtAuthenticationConverter(new JwtExecutionContextFactory());
    private final ExecutionContextFilter filter = new ExecutionContextFilter(request -> "correlation-1");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @MethodSource("io.github.ande1922.moduvera.testing.TenantIdContractValues#validTenantIds")
    void installsAndClearsAServiceExecutionContextUsingTheTenantHeader(String tenantId)
            throws Exception {
        authenticate(jwt(ActorType.SERVICE, "order-service")
                .claim(ModuveraJwtClaims.PERMISSIONS, List.of("inventory:reserve"))
                .build());
        var request = new MockHttpServletRequest();
        request.addHeader(ExecutionContextFilter.TENANT_HEADER, tenantId);

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
            var current = ExecutionContextHolder.require();
            assertThat(current.tenantId().value()).isEqualTo(tenantId);
            assertThat(current.actor().type()).isEqualTo(ActorType.SERVICE);
            assertThat(current.correlationId()).isEqualTo("correlation-1");
        });

        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("io.github.ande1922.moduvera.testing.TenantIdContractValues#invalidTenantIds")
    void rejectsInvalidServiceTenantHeaders(String tenantId) {
        authenticate(jwt(ActorType.SERVICE, "order-service").build());
        var request = new MockHttpServletRequest();
        request.addHeader(ExecutionContextFilter.TENANT_HEADER, tenantId);

        assertThatThrownBy(() -> filter.doFilter(
                        request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {}))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void preventsAHeaderFromOverridingAUserTenant() {
        authenticate(jwt(ActorType.USER, "user-42")
                .claim(ModuveraJwtClaims.TENANT_ID, "tenant-a")
                .build());
        var request = new MockHttpServletRequest();
        request.addHeader(ExecutionContextFilter.TENANT_HEADER, "tenant-b");

        assertThatThrownBy(() -> filter.doFilter(
                        request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {}))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("cannot override");
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private void authenticate(Jwt jwt) {
        var authentication = (ModuveraJwtAuthenticationToken) converter.convert(jwt);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
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
