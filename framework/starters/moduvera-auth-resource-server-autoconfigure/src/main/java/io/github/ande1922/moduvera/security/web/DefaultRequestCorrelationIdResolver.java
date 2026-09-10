package io.github.ande1922.moduvera.security.web;

import io.github.ande1922.moduvera.web.ServletRequestDiagnostics;
import jakarta.servlet.http.HttpServletRequest;

public final class DefaultRequestCorrelationIdResolver implements RequestCorrelationIdResolver {

    public static final String HEADER = "X-Correlation-Id";
    public static final String REQUEST_ATTRIBUTE = "io.github.ande1922.moduvera.correlationId";

    @Override
    public String resolve(HttpServletRequest request) {
        return ServletRequestDiagnostics.establish(request, false).correlationId();
    }
}
