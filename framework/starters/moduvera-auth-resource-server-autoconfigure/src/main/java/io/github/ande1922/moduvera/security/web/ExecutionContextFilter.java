package io.github.ande1922.moduvera.security.web;

import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationToken;
import io.github.ande1922.moduvera.security.jwt.TrustedJwtPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class ExecutionContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "Tenant-Id";

    private final RequestCorrelationIdResolver correlationIds;

    public ExecutionContextFilter(RequestCorrelationIdResolver correlationIds) {
        this.correlationIds = correlationIds;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ModuveraJwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        TrustedJwtPrincipal principal = token.trustedPrincipal();
        TenantId tenantId = resolveTenant(request, principal);
        ExecutionContext context = new ExecutionContext(
                tenantId, principal.actor(), principal.initiator(), correlationIds.resolve(request));
        try (ExecutionContextHolder.Scope ignored = ExecutionContextHolder.open(context)) {
            filterChain.doFilter(request, response);
        }
    }

    private static TenantId resolveTenant(HttpServletRequest request, TrustedJwtPrincipal principal) {
        String headerValue = request.getHeader(TENANT_HEADER);
        if (principal.actor().type() == ActorType.SERVICE) {
            if (headerValue == null || headerValue.isBlank()) {
                throw new AccessDeniedException("SERVICE requests to tenant use cases require Tenant-Id");
            }
            TenantId requestedTenant = tenant(headerValue);
            principal.assertedTenant().ifPresent(asserted -> rejectMismatch(asserted, requestedTenant));
            return requestedTenant;
        }

        TenantId assertedTenant = principal.assertedTenant().orElseThrow();
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
            throw new AccessDeniedException("Tenant-Id cannot override the authenticated tenant");
        }
    }
}
