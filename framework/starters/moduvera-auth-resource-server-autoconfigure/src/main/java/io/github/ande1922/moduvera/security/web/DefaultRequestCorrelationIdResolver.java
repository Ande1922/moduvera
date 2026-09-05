package io.github.ande1922.moduvera.security.web;

import io.github.ande1922.moduvera.context.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class DefaultRequestCorrelationIdResolver implements RequestCorrelationIdResolver {

    public static final String HEADER = "X-Correlation-Id";
    public static final String REQUEST_ATTRIBUTE = "io.github.ande1922.moduvera.correlationId";

    @Override
    public String resolve(HttpServletRequest request) {
        Object established = request.getAttribute(REQUEST_ATTRIBUTE);
        if (established != null) {
            return established.toString();
        }
        String candidate = request.getHeader(HEADER);
        String resolved = ExecutionContext.isValidCorrelationId(candidate)
                ? candidate
                : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, resolved);
        return resolved;
    }
}
