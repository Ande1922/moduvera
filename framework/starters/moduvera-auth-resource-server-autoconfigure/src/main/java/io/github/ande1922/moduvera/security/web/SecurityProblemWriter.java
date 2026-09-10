package io.github.ande1922.moduvera.security.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

public final class SecurityProblemWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String UNAUTHENTICATED = "security.unauthenticated";
    private static final String FORBIDDEN = "security.forbidden";

    private final ObjectMapper objectMapper;
    private final RequestCorrelationIdResolver correlationIds;

    public SecurityProblemWriter(ObjectMapper objectMapper, RequestCorrelationIdResolver correlationIds) {
        this.objectMapper = objectMapper;
        this.correlationIds = correlationIds;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHENTICATED, "Authentication is required");
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException, ServletException {
        write(request, response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN, "Access is denied");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:problem:" + code);
        problem.put("title", status == HttpServletResponse.SC_UNAUTHORIZED ? "Authentication required" : "Forbidden");
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("code", code);
        problem.put("correlationId", correlationIds.resolve(request));
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
