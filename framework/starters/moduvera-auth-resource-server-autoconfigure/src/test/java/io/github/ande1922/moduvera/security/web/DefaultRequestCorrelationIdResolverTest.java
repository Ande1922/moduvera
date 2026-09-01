package io.github.ande1922.moduvera.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DefaultRequestCorrelationIdResolverTest {

    private final DefaultRequestCorrelationIdResolver resolver =
            new DefaultRequestCorrelationIdResolver();

    @Test
    void reusesCorrelationIdEstablishedByTheWebStarter() {
        var request = new MockHttpServletRequest();
        request.setAttribute(DefaultRequestCorrelationIdResolver.REQUEST_ATTRIBUTE, "corr-42");

        assertThat(resolver.resolve(request)).isEqualTo("corr-42");
    }
}
