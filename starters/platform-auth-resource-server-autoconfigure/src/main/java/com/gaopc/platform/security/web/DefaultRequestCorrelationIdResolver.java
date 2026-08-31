package com.gaopc.platform.security.web;

import com.gaopc.platform.context.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class DefaultRequestCorrelationIdResolver implements RequestCorrelationIdResolver {

    public static final String HEADER = "X-Correlation-Id";
    public static final String REQUEST_ATTRIBUTE = "com.gaopc.platform.correlationId";

    @Override
    public String resolve(HttpServletRequest request) {
        Object established = request.getAttribute(REQUEST_ATTRIBUTE);
        if (established != null) {
            return established.toString();
        }
        String candidate = request.getHeader(HEADER);
        if (ExecutionContext.isValidCorrelationId(candidate)) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
