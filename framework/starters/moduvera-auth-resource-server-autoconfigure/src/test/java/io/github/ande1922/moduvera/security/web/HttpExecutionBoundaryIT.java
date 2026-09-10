package io.github.ande1922.moduvera.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
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
import java.util.Set;
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
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
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
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootTest(
        classes = HttpExecutionBoundaryIT.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.threads.virtual.enabled=false",
            "moduvera.web.internal-ingress=true",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
            "spring.security.oauth2.resourceserver.jwt.audiences=boundary-test"
        })
class HttpExecutionBoundaryIT {

    private static final List<tools.jackson.databind.JsonNode> DIAGNOSTICS = new CopyOnWriteArrayList<>();
    private final ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> diagnosticAppender =
            new ch.qos.logback.core.AppenderBase<>() {
                private final io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter formatter =
                        new io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter(new org.springframework.mock.env.MockEnvironment());
                private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
                @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                    DIAGNOSTICS.add(mapper.readTree(formatter.format(event)));
                }
            };
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
        DIAGNOSTICS.clear();
        diagnosticAppender.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(diagnosticAppender);
    }

    @org.junit.jupiter.api.AfterEach
    void detachDiagnosticOutput() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(diagnosticAppender);
        diagnosticAppender.stop();
    }

    @Test
    void repairsInternalCorrelationOnceAndCapturesTrustedIdentityAfterScopeCloses() throws Exception {
        String user = token(ENCODER, "diagnostic-user", "tenant-a", List.of("tenant:read"));
        for (String candidate : new String[] {null, "invalid value", "valid-C.42"}) {
            DIAGNOSTICS.clear();
            var response = get("/managed/tenant", user, "tenant-a", candidate);
            assertThat(response.statusCode()).isEqualTo(200);
            String correlation = response.headers().firstValue("X-Correlation-Id").orElseThrow();
            if ("valid-C.42".equals(candidate)) {
                assertThat(correlation).isEqualTo(candidate);
            } else {
                assertThat(java.util.UUID.fromString(correlation).version()).isEqualTo(4);
            }
            var event = diagnosticEvent(correlation);
            assertThat(event.path("actor_id").asString()).isEqualTo("diagnostic-user");
            assertThat(event.path("tenant_id").asString()).isEqualTo("tenant-a");
            assertThat(event.path("user_id").asString()).isEqualTo("diagnostic-user");
            assertThat(evidence.contextAfterDispatch()).containsOnly(false);
            var recovery = DIAGNOSTICS.stream().filter(line -> "WARN".equals(line.path("log").path("level").asString())
                    && line.path("log").path("logger").asString().endsWith("ServletRequestDiagnostics")).toList();
            assertThat(recovery).hasSize("valid-C.42".equals(candidate) ? 0 : 1);
            assertThat(DIAGNOSTICS.toString()).doesNotContain("invalid value");
        }
    }

    private static tools.jackson.databind.JsonNode diagnosticEvent(String correlation) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var matches = DIAGNOSTICS.stream().filter(event -> "http.request".equals(event.path("log").path("logger").asString())
                    && correlation.equals(event.path("correlation_id").asString())).toList();
            if (!matches.isEmpty()) {
                assertThat(matches).hasSize(1);
                return matches.getFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("missing request completion");
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
        var deniedDiagnostic = diagnosticEvent("corr-tenant-denied");
        assertThat(deniedDiagnostic.path("actor_id").asString()).isEqualTo("platform-user");
        assertThat(deniedDiagnostic.has("tenant_id")).isFalse();
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

        String noPermission = token(ENCODER, "platform-user", "tenant-a", List.of());
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

    @Test
    void reusesOneGeneratedCorrelationAcrossProblemAsyncAndSelectedErrorDispatches()
            throws Exception {
        String alice = token(ENCODER, "alice", "tenant-a", List.of("tenant:read"));

        HttpResponse<String> forbidden =
                get("/managed/context-then-denied", alice, null, null);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        List<ObservedContext> forbiddenContexts = evidence.observedContexts();
        assertThat(forbiddenContexts).hasSize(1);
        assertFullIdentity(
                forbiddenContexts.getFirst(),
                DispatcherType.REQUEST,
                ExecutionScope.platform(),
                "alice");
        assertThat(forbidden.body())
                .contains("\"correlationId\":\""
                        + forbiddenContexts.getFirst().correlationId()
                        + "\"");

        evidence.reset();
        HttpResponse<String> async = get("/managed/async", alice, null, null);
        assertThat(async.statusCode()).isEqualTo(200);
        assertThat(evidence.observedContexts())
                .satisfiesExactly(
                        observed -> assertFullIdentity(
                                observed,
                                DispatcherType.REQUEST,
                                ExecutionScope.platform(),
                                "alice"),
                        observed -> assertFullIdentity(
                                observed,
                                DispatcherType.ASYNC,
                                ExecutionScope.platform(),
                                "alice"));
        assertThat(evidence.observedContexts())
                .extracting(ObservedContext::correlationId)
                .containsOnly(evidence.observedContexts().getFirst().correlationId());

        evidence.reset();
        HttpResponse<String> error = get("/managed/error", alice, null, null);
        assertThat(error.statusCode()).isEqualTo(500);
        assertThat(evidence.observedContexts())
                .satisfiesExactly(
                        observed -> assertFullIdentity(
                                observed,
                                DispatcherType.REQUEST,
                                ExecutionScope.platform(),
                                "alice"),
                        observed -> assertFullIdentity(
                                observed,
                                DispatcherType.ERROR,
                                ExecutionScope.tenant(new TenantId("tenant-a")),
                                "alice"));
        assertThat(evidence.observedContexts())
                .extracting(ObservedContext::correlationId)
                .containsOnly(evidence.observedContexts().getFirst().correlationId());
        assertThat(evidence.contextAfterDispatch()).containsOnly(false);
    }

    private HttpResponse<String> get(
            String path, String bearerToken, String tenantId, String correlationId)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        if (tenantId != null) {
            request.header(ExecutionContextHandlerInterceptor.TENANT_HEADER, tenantId);
        }
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertFullIdentity(
            ObservedContext observed,
            DispatcherType dispatcherType,
            ExecutionScope scope,
            String subject) {
        assertThat(observed.dispatcherType()).isEqualTo(dispatcherType);
        assertThat(observed.scope()).isEqualTo(scope);
        assertThat(observed.actor())
                .isEqualTo(new Actor(ActorType.USER, subject, Set.of("tenant:read")));
        assertThat(observed.initiator()).isEqualTo(new Initiator(ActorType.USER, subject));
        assertThat(observed.correlationId()).isNotBlank();
    }

    private static void assertProblem(
            HttpResponse<String> response, int status, String code, String correlationId) throws InterruptedException {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains(correlationId);
        var event = diagnosticEvent(correlationId);
        assertThat(event.path("event").path("outcome").asString()).isEqualTo("failure");
        assertThat(event.path("http").path("response").path("status_code").asInt()).isEqualTo(status);
        if (status == 401) {
            assertThat(event.has("actor_id")).isFalse();
        }
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
                    .manageHandlers(BasicErrorController.class)
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

        @Bean
        WebMvcConfigurer observedContextConfigurer(InvocationEvidence evidence) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                                @Override
                                public boolean preHandle(
                                        HttpServletRequest request,
                                        HttpServletResponse response,
                                        Object handler) {
                                    ExecutionContextHolder.current().ifPresent(context ->
                                            evidence.observe(request.getDispatcherType(), context));
                                    return true;
                                }
                            })
                            .order(Ordered.LOWEST_PRECEDENCE);
                }
            };
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

        @GetMapping("/context-then-denied")
        String contextThenDenied() {
            ExecutionContextHolder.require();
            throw new AccessDeniedException("expected denial after context installation");
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
        private final List<ObservedContext> observedContexts = new CopyOnWriteArrayList<>();

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

        void observe(DispatcherType dispatcherType, ExecutionContext context) {
            observedContexts.add(new ObservedContext(
                    dispatcherType,
                    context.scope(),
                    context.actor(),
                    context.initiator(),
                    context.correlationId()));
        }

        List<ObservedContext> observedContexts() {
            return new ArrayList<>(observedContexts);
        }

        void reset() {
            businessInvocations.set(0);
            dispatches.clear();
            contextAfterDispatch.clear();
            observedContexts.clear();
        }
    }

    record ObservedContext(
            DispatcherType dispatcherType,
            ExecutionScope scope,
            Actor actor,
            Initiator initiator,
            String correlationId) {}

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
