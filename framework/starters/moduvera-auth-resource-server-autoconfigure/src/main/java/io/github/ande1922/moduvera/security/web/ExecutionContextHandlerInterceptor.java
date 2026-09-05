package io.github.ande1922.moduvera.security.web;

import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationToken;
import io.github.ande1922.moduvera.security.jwt.TrustedJwtPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/** Installs one trusted execution scope for each managed MVC dispatch. */
public final class ExecutionContextHandlerInterceptor implements AsyncHandlerInterceptor {

    public static final String TENANT_HEADER = "Tenant-Id";

    private static final String SCOPE_ATTRIBUTE =
            ExecutionContextHandlerInterceptor.class.getName() + ".scope";

    private final ExecutionContextHandlerSelection handlers;
    private final RequestCorrelationIdResolver correlationIds;

    public ExecutionContextHandlerInterceptor(
            ExecutionContextHandlerSelection handlers, RequestCorrelationIdResolver correlationIds) {
        this.handlers = handlers;
        this.correlationIds = correlationIds;
    }

    @Override
    @SuppressWarnings("PMD.CloseResource") // Closed by afterCompletion or afterConcurrentHandlingStarted.
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod) || !handlers.includes(handlerMethod)) {
            return true;
        }
        if (request.getAttribute(SCOPE_ATTRIBUTE) != null) {
            throw new IllegalStateException(
                    "an ExecutionContext scope is already open for this MVC dispatch");
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ModuveraJwtAuthenticationToken token)
                || !authentication.isAuthenticated()) {
            throw new InsufficientAuthenticationException(
                    "A trusted resource-server identity is required at this execution boundary");
        }

        TrustedJwtPrincipal principal = token.trustedPrincipal();
        ExecutionScope executionScope = switch (mode(handlerMethod)) {
            case PLATFORM -> ExecutionScope.platform();
            case TENANT -> ExecutionScope.tenant(resolveTenant(request, principal));
        };
        ExecutionContext context = new ExecutionContext(
                executionScope,
                principal.actor(),
                principal.initiator(),
                correlationIds.resolve(request));
        request.setAttribute(SCOPE_ATTRIBUTE, ExecutionContextHolder.open(context));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        closeScope(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        closeScope(request);
    }

    private static ExecutionBoundaryMode mode(HandlerMethod handler) {
        Method method = handler.getMethod();
        ExecutionBoundary methodBoundary =
                AnnotatedElementUtils.findMergedAnnotation(method, ExecutionBoundary.class);
        if (methodBoundary != null) {
            return methodBoundary.value();
        }
        ExecutionBoundary typeBoundary = AnnotatedElementUtils.findMergedAnnotation(
                handler.getBeanType(), ExecutionBoundary.class);
        return typeBoundary == null ? ExecutionBoundaryMode.TENANT : typeBoundary.value();
    }

    private static TenantId resolveTenant(
            HttpServletRequest request, TrustedJwtPrincipal principal) {
        String headerValue = request.getHeader(TENANT_HEADER);
        if (principal.actor().type() == ActorType.SERVICE) {
            if (headerValue == null || headerValue.isBlank()) {
                throw new AccessDeniedException(
                        "SERVICE requests to tenant use cases require Tenant-Id");
            }
            TenantId requestedTenant = tenant(headerValue);
            principal.assertedTenant().ifPresent(asserted -> rejectMismatch(asserted, requestedTenant));
            return requestedTenant;
        }

        TenantId assertedTenant = principal.assertedTenant()
                .orElseThrow(() -> new AccessDeniedException(
                        "USER requests to tenant use cases require an authenticated tenant"));
        if (headerValue != null && !headerValue.isBlank()) {
            rejectMismatch(assertedTenant, tenant(headerValue));
        }
        return assertedTenant;
    }

    private static TenantId tenant(String value) {
        try {
            return new TenantId(value);
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Tenant-Id is invalid", exception);
        }
    }

    private static void rejectMismatch(TenantId assertedTenant, TenantId requestedTenant) {
        if (!assertedTenant.equals(requestedTenant)) {
            throw new AccessDeniedException(
                    "Tenant-Id cannot override the authenticated tenant");
        }
    }

    @SuppressWarnings("PMD.CloseResource") // The scope is closed immediately below.
    private static void closeScope(HttpServletRequest request) {
        Object value = request.getAttribute(SCOPE_ATTRIBUTE);
        if (value instanceof ExecutionContextHolder.Scope scope) {
            scope.close();
            request.removeAttribute(SCOPE_ATTRIBUTE);
        }
    }
}
