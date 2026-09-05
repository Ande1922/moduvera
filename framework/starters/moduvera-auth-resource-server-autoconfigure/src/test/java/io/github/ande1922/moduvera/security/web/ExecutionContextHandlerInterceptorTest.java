package io.github.ande1922.moduvera.security.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.security.jwt.JwtExecutionContextFactory;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationConverter;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationToken;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtClaims;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;

class ExecutionContextHandlerInterceptorTest {

    private final ModuveraJwtAuthenticationConverter converter =
            new ModuveraJwtAuthenticationConverter(new JwtExecutionContextFactory());
    private final ExecutionContextHandlerInterceptor interceptor = new ExecutionContextHandlerInterceptor(
            ExecutionContextHandlerSelection.builder()
                    .managePackage(getClass().getPackageName())
                    .excludeHandlers(ExcludedHandler.class)
                    .build(),
            request -> "correlation-1");

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void resolvesMethodThenClassThenDefaultBoundary() throws Exception {
        authenticate(jwt(ActorType.USER, "platform-user").build());

        assertContext(
                handler(DeclaredHandler.class, "platform"),
                new MockHttpServletRequest(),
                ExecutionScope.Platform.class);

        var tenantRequest = new MockHttpServletRequest();
        tenantRequest.addHeader(ExecutionContextHandlerInterceptor.TENANT_HEADER, "tenant-a");
        authenticate(jwt(ActorType.USER, "tenant-user")
                .claim(ModuveraJwtClaims.TENANT_ID, "tenant-a")
                .build());
        assertContext(handler(DeclaredHandler.class, "tenant"), tenantRequest, ExecutionScope.Tenant.class);
        assertContext(handler(DefaultHandler.class, "handle"), tenantRequest, ExecutionScope.Tenant.class);
    }

    @Test
    void allowsAUserWithoutTenantOnlyAtAPlatformBoundary() throws Exception {
        authenticate(jwt(ActorType.USER, "platform-user")
                .claim(ModuveraJwtClaims.PERMISSIONS, List.of("platform:read"))
                .build());
        var request = new MockHttpServletRequest();
        request.addHeader(ExecutionContextHandlerInterceptor.TENANT_HEADER, "tenant-header-is-ignored");

        interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                handler(DeclaredHandler.class, "platform"));
        var context = ExecutionContextHolder.require();
        assertThat(context.scope()).isInstanceOf(ExecutionScope.Platform.class);
        assertThat(context.actor().subjectId()).isEqualTo("platform-user");
        assertThat(context.initiator().subjectId()).isEqualTo("platform-user");
        assertThat(context.correlationId()).isEqualTo("correlation-1");
        interceptor.afterCompletion(
                request,
                new MockHttpServletResponse(),
                handler(DeclaredHandler.class, "platform"),
                null);

        assertThatThrownBy(() -> interceptor.preHandle(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        handler(DefaultHandler.class, "handle")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsTenantConflictsAndInvalidOrMissingServiceTargetsBeforeOpeningScope() throws Exception {
        authenticate(jwt(ActorType.USER, "tenant-user")
                .claim(ModuveraJwtClaims.TENANT_ID, "tenant-a")
                .build());
        var conflict = new MockHttpServletRequest();
        conflict.addHeader(ExecutionContextHandlerInterceptor.TENANT_HEADER, "tenant-b");
        assertThatThrownBy(() -> interceptor.preHandle(
                        conflict,
                        new MockHttpServletResponse(),
                        handler(DefaultHandler.class, "handle")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("cannot override");

        authenticate(jwt(ActorType.SERVICE, "calling-service").build());
        assertThatThrownBy(() -> interceptor.preHandle(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        handler(DefaultHandler.class, "handle")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("require Tenant-Id");

        var invalid = new MockHttpServletRequest();
        invalid.addHeader(ExecutionContextHandlerInterceptor.TENANT_HEADER, "bad/value");
        assertThatThrownBy(() -> interceptor.preHandle(
                        invalid,
                        new MockHttpServletResponse(),
                        handler(DefaultHandler.class, "handle")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void excludesNonBusinessHandlersWithoutChangingTheirSecurityIdentity() throws Exception {
        authenticate(jwt(ActorType.USER, "platform-user").build());

        assertThat(interceptor.preHandle(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        handler(ExcludedHandler.class, "handle")))
                .isTrue();
        assertThat(ExecutionContextHolder.current()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated())
                .isTrue();
    }

    @Test
    void closesEachActualDispatchScopeForAsyncHandoffAndErrors() throws Exception {
        authenticate(jwt(ActorType.USER, "tenant-user")
                .claim(ModuveraJwtClaims.TENANT_ID, "tenant-a")
                .build());
        HandlerMethod handler = handler(DefaultHandler.class, "handle");
        var asyncRequest = new MockHttpServletRequest();
        interceptor.preHandle(asyncRequest, new MockHttpServletResponse(), handler);
        interceptor.afterConcurrentHandlingStarted(asyncRequest, new MockHttpServletResponse(), handler);
        assertThat(ExecutionContextHolder.current()).isEmpty();

        var errorRequest = new MockHttpServletRequest();
        interceptor.preHandle(errorRequest, new MockHttpServletResponse(), handler);
        interceptor.afterCompletion(
                errorRequest,
                new MockHttpServletResponse(),
                handler,
                new IllegalStateException("controller failed"));
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private void assertContext(
            HandlerMethod handler,
            MockHttpServletRequest request,
            Class<? extends ExecutionScope> expectedScope)
            throws Exception {
        interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        assertThat(ExecutionContextHolder.require().scope()).isInstanceOf(expectedScope);
        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);
        assertThat(ExecutionContextHolder.current()).isEmpty();
    }

    private void authenticate(Jwt jwt) {
        var authentication = (ModuveraJwtAuthenticationToken) converter.convert(jwt);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static HandlerMethod handler(Class<?> type, String methodName) throws Exception {
        Object bean = type.getDeclaredConstructor().newInstance();
        Method method = type.getDeclaredMethod(methodName);
        return new HandlerMethod(bean, method);
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

    @ExecutionBoundary(ExecutionBoundaryMode.PLATFORM)
    static class DeclaredHandler {

        @ExecutionBoundary(ExecutionBoundaryMode.TENANT)
        void tenant() {}

        void platform() {}
    }

    static class DefaultHandler {
        void handle() {}
    }

    static class ExcludedHandler {
        void handle() {}
    }
}
