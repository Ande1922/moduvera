package io.github.ande1922.moduvera.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.ObjectMapper;

class SecurityProblemWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecurityProblemWriter writer = new SecurityProblemWriter(objectMapper, request -> "corr-security-42");

    @Test
    void rendersUnauthenticatedProblemDetails() throws Exception {
        var response = new MockHttpServletResponse();

        writer.commence(
                new MockHttpServletRequest(),
                response,
                new InsufficientAuthenticationException("authentication required"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var problem = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("code").asString()).isEqualTo("security.unauthenticated");
        assertThat(problem.get("correlationId").asString()).isEqualTo("corr-security-42");
    }

    @Test
    void rendersFilterChainAccessDenialAsForbiddenProblemDetails() throws Exception {
        var response = new MockHttpServletResponse();

        writer.handle(
                new MockHttpServletRequest(),
                response,
                new AccessDeniedException("access denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var problem = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(problem.get("status").asInt()).isEqualTo(403);
        assertThat(problem.get("code").asString()).isEqualTo("security.forbidden");
        assertThat(problem.get("correlationId").asString()).isEqualTo("corr-security-42");
    }
}
