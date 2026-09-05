package io.github.ande1922.moduvera.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtClaims;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

@SpringBootTest(
        classes = HttpExecutionBoundaryIT.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.threads.virtual.enabled=false",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
            "spring.security.oauth2.resourceserver.jwt.audiences=boundary-test"
        })
class HttpExecutionBoundaryIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final RSAKey WRONG_KEY = signingKey();
    private static final JwtEncoder ENCODER = encoder(SIGNING_KEY);
    private static final JwtEncoder WRONG_ENCODER = encoder(WRONG_KEY);

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private InvocationEvidence evidence;

    @BeforeEach
    void resetEvidence() {
        evidence.reset();
    }

    @Test
    void authenticatesSignedUsersBeforeInstallingHandlerSelectedPlatformOrTenantScopes()
            throws Exception {
        String platformUser = token(ENCODER, "platform-user", null, List.of("platform:read"));
        HttpResponse<String> platform = get(
                "/managed/platform", platformUser, "tenant-header-is-ignored", "corr-platform");
        assertThat(platform.statusCode()).isEqualTo(200);
        assertThat(platform.body())
                .isEqualTo("PLATFORM:platform-user:platform-user:corr-platform:false");

        HttpResponse<String> tenantDenied =
                get("/managed/tenant", platformUser, null, "corr-tenant-denied");
        assertProblem(tenantDenied, 403, "security.forbidden", "corr-tenant-denied");
        assertThat(evidence.businessInvocations()).isEqualTo(1);

        String tenantUser = token(ENCODER, "tenant-user", "tenant-a", List.of("tenant:read"));
        HttpResponse<String> tenant =
                get("/managed/tenant", tenantUser, "tenant-a", "corr-tenant");
        assertThat(tenant.statusCode()).isEqualTo(200);
        assertThat(tenant.body())
                .isEqualTo("TENANT:tenant-a:tenant-user:tenant-user:corr-tenant:false");

        HttpResponse<String> defaultTenant =
                get("/default", tenantUser, null, "corr-default");
        assertThat(defaultTenant.statusCode()).isEqualTo(200);
        assertThat(defaultTenant.body()).startsWith("TENANT:tenant-a:");
    }

    @Test
    void rejectsAuthenticationTenantConflictsAndAuthorizationBeforeSideEffects()
            throws Exception {
        HttpResponse<String> missing = get("/managed/platform", null, null, "corr-missing");
        assertProblem(missing, 401, "security.unauthenticated", "corr-missing");

        String invalidSignature = token(WRONG_ENCODER, "user", null, List.of("platform:read"));
        HttpResponse<String> invalid =
                get("/managed/platform", invalidSignature, null, "corr-invalid");
        assertProblem(invalid, 401, "security.unauthenticated", "corr-invalid");

        String tenantUser = token(ENCODER, "tenant-user", "tenant-a", List.of("tenant:read"));
        HttpResponse<String> conflict =
                get("/managed/tenant", tenantUser, "tenant-b", "corr-conflict");
        assertProblem(conflict, 403, "security.forbidden", "corr-conflict");

        String noPermission = token(ENCODER, "platform-user", null, List.of());
        HttpResponse<String> forbidden =
                get("/managed/platform", noPermission, null, "corr-forbidden");
        assertProblem(forbidden, 403, "security.forbidden", "corr-forbidden");

        HttpResponse<String> downstreamFailure =
                get("/excluded/missing-context", noPermission, null, "corr-downstream");
        assertThat(downstreamFailure.statusCode()).isEqualTo(500);
        assertThat(downstreamFailure.body()).doesNotContain("security.unauthenticated");

        String serviceNoPermission = token(
                ENCODER, "SERVICE", "calling-service", null, List.of());
        HttpResponse<String> serviceForbidden = get(
                "/managed/tenant", serviceNoPermission, "tenant-a", "corr-service-forbidden");
        assertProblem(
                serviceForbidden, 403, "security.forbidden", "corr-service-forbidden");
        assertThat(evidence.businessInvocations()).isZero();
    }

    @Test
    void keepsExcludedHandlersAuthenticatedWithoutImposingATenantContext() throws Exception {
        String platformUser = token(ENCODER, "platform-user", null, List.of());

        HttpResponse<String> response =
                get("/excluded", platformUser, null, "corr-excluded");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("platform-user:false");
    }

    @Test
    void isolatesSameTenantRequestsAndClosesActualSyncAsyncAndErrorDispatchThreads()
            throws Exception {
        String alice = token(ENCODER, "alice", "tenant-a", List.of("tenant:read"));
        String bob = token(ENCODER, "bob", "tenant-a", List.of("tenant:read"));

        assertThat(get("/default", alice, null, "corr-alice").body())
                .contains(":alice:alice:corr-alice:");
        assertThat(get("/default", bob, null, "corr-bob").body())
                .contains(":bob:bob:corr-bob:");

        HttpResponse<String> async = get("/managed/async", alice, null, "corr-async");
        assertThat(async.statusCode()).isEqualTo(200);
        assertThat(async.body()).isEqualTo("async-complete");

        HttpResponse<String> error = get("/managed/error", alice, null, "corr-error");
        assertThat(error.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());

        assertThat(evidence.dispatches())
                .contains(DispatcherType.REQUEST)
                .contains(DispatcherType.ASYNC)
                .contains(DispatcherType.ERROR);
        assertThat(evidence.contextAfterDispatch()).containsOnly(false);
    }

    private HttpResponse<String> get(
            String path, String bearerToken, String tenantId, String correlationId)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Correlation-Id", correlationId)
                .GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        if (tenantId != null) {
            request.header(ExecutionContextHandlerInterceptor.TENANT_HEADER, tenantId);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(
            HttpResponse<String> response, int status, String code, String correlationId) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(response.body())
                .contains("\"code\":\"" + code + "\"")
                .contains("\"correlationId\":\"" + correlationId + "\"");
    }

    private static String token(
            JwtEncoder encoder,
            String subject,
            String tenantId,
            List<String> permissions) {
        return token(encoder, "USER", subject, tenantId, permissions);
    }

    private static String token(
            JwtEncoder encoder,
            String actorType,
            String subject,
            String tenantId,
            List<String> permissions) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("https://identity.example.test")
                .audience(List.of("boundary-test"))
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(ModuveraJwtClaims.ACTOR_TYPE, actorType)
                .claim(ModuveraJwtClaims.PERMISSIONS, permissions);
        if (tenantId != null) {
            claims.claim(ModuveraJwtClaims.TENANT_ID, tenantId);
        }
        return encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(java.util.UUID.randomUUID().toString()).generate();
        } catch (com.nimbusds.jose.JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JwtEncoder encoder(RSAKey key) {
        return new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        JwtDecoder jwtDecoder() throws com.nimbusds.jose.JOSEException {
            return NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
        }

        @Bean
        ExecutionContextHandlerSelection managedHttpHandlers() {
            return ExecutionContextHandlerSelection.builder()
                    .managePackage(HttpExecutionBoundaryIT.class.getPackageName())
                    .excludeHandlers(ExcludedController.class)
                    .build();
        }

        @Bean
        InvocationEvidence invocationEvidence() {
            return new InvocationEvidence();
        }

        @Bean
        ManagedController managedController(InvocationEvidence evidence) {
            return new ManagedController(evidence);
        }

        @Bean
        DefaultController defaultController() {
            return new DefaultController();
        }

        @Bean
        ExcludedController excludedController() {
            return new ExcludedController();
        }

        @Bean
        DispatchCleanupProbe dispatchCleanupProbe(InvocationEvidence evidence) {
            return new DispatchCleanupProbe(evidence);
        }
    }

    @RestController
    @RequestMapping("/managed")
    @ExecutionBoundary(ExecutionBoundaryMode.PLATFORM)
    static class ManagedController {

        private final InvocationEvidence evidence;

        ManagedController(InvocationEvidence evidence) {
            this.evidence = evidence;
        }

        @GetMapping("/platform")
        String platform() {
            var context = ExecutionContextHolder.require();
            if (!context.actor().permissions().contains("platform:read")) {
                throw new AccessDeniedException("platform:read is required");
            }
            evidence.businessInvocation();
            return describe(context.scope());
        }

        @GetMapping("/tenant")
        @ExecutionBoundary(ExecutionBoundaryMode.TENANT)
        String tenant() {
            var context = ExecutionContextHolder.require();
            if (!context.actor().permissions().contains("tenant:read")) {
                throw new AccessDeniedException("tenant:read is required");
            }
            evidence.businessInvocation();
            return describe(context.scope());
        }

        @GetMapping("/async")
        Callable<String> async() {
            ExecutionContextHolder.require();
            return () -> "async-complete";
        }

        @GetMapping("/error")
        String error() {
            ExecutionContextHolder.require();
            throw new IllegalStateException("expected test error");
        }
    }

    @RestController
    static class DefaultController {

        @GetMapping("/default")
        String tenant() {
            return describe(ExecutionContextHolder.require().scope());
        }
    }

    @RestController
    static class ExcludedController {

        @GetMapping("/excluded")
        String excluded(org.springframework.security.core.Authentication authentication) {
            return authentication.getName() + ':' + ExecutionContextHolder.current().isPresent();
        }

        @GetMapping("/excluded/missing-context")
        String missingContext() {
            return ExecutionContextHolder.require().correlationId();
        }
    }

    private static String describe(ExecutionScope scope) {
        var context = ExecutionContextHolder.require();
        String scopeValue = scope instanceof ExecutionScope.Tenant tenant
                ? "TENANT:" + tenant.tenantId().value()
                : "PLATFORM";
        return scopeValue
                + ':'
                + context.actor().subjectId()
                + ':'
                + context.initiator().subjectId()
                + ':'
                + context.correlationId()
                + ':'
                + Thread.currentThread().isVirtual();
    }

    static final class InvocationEvidence {

        private final AtomicInteger businessInvocations = new AtomicInteger();
        private final List<DispatcherType> dispatches = new CopyOnWriteArrayList<>();
        private final List<Boolean> contextAfterDispatch = new CopyOnWriteArrayList<>();

        void businessInvocation() {
            businessInvocations.incrementAndGet();
        }

        int businessInvocations() {
            return businessInvocations.get();
        }

        void afterDispatch(DispatcherType dispatcherType) {
            dispatches.add(dispatcherType);
            contextAfterDispatch.add(ExecutionContextHolder.current().isPresent());
        }

        List<DispatcherType> dispatches() {
            return new ArrayList<>(dispatches);
        }

        List<Boolean> contextAfterDispatch() {
            return new ArrayList<>(contextAfterDispatch);
        }

        void reset() {
            businessInvocations.set(0);
            dispatches.clear();
            contextAfterDispatch.clear();
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    static final class DispatchCleanupProbe extends OncePerRequestFilter {

        private final InvocationEvidence evidence;

        DispatchCleanupProbe(InvocationEvidence evidence) {
            this.evidence = evidence;
        }

        @Override
        protected boolean shouldNotFilterAsyncDispatch() {
            return false;
        }

        @Override
        protected boolean shouldNotFilterErrorDispatch() {
            return false;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain)
                throws ServletException, IOException {
            try {
                filterChain.doFilter(request, response);
            } finally {
                evidence.afterDispatch(request.getDispatcherType());
            }
        }
    }
}
